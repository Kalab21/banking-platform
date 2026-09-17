package com.bankingplatform.payment.service.impl;

import com.bankingplatform.payment.client.TransactionClient;
import com.bankingplatform.payment.dto.*;
import com.bankingplatform.payment.exception.PaymentException;
import com.bankingplatform.payment.exception.ResourceNotFoundException;
import com.bankingplatform.payment.kafka.producer.PaymentEventProducer;
import com.bankingplatform.payment.mapper.PaymentMapper;
import com.bankingplatform.payment.model.*;
import com.bankingplatform.payment.repository.AuditLogRepository;
import com.bankingplatform.payment.repository.PaymentRepository;
import com.bankingplatform.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final AuditLogRepository auditLogRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentEventProducer eventProducer;
    private final TransactionClient transactionClient;

    @Override
    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        validatePaymentRequest(request);

        Payment payment = Payment.builder()
                .paymentRef(UUID.randomUUID().toString())
                .payerAccountId(request.getPayerAccountId())
                .beneficiaryId(request.getBeneficiaryId())
                .payeeAccountId(request.getPayeeAccountId())
                .payeeExternalRef(request.getPayeeExternalRef())
                .paymentType(request.getPaymentType())
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .description(request.getDescription())
                .recurring(request.isRecurring())
                .recurrencePattern(request.getRecurrencePattern())
                .endDate(request.getEndDate())
                .scheduledAt(request.getScheduledAt())
                .build();

        if (payment.isRecurring() && payment.getRecurrencePattern() != null) {
            payment.setNextExecutionDate(calculateNextDate(LocalDate.now(), payment.getRecurrencePattern()));
        }

        // Save first — entity needs an ID before Kafka event fires
        Payment saved = paymentRepository.save(payment);

        // Immediate execution (no scheduledAt or scheduledAt not in the future)
        if (request.getScheduledAt() == null || !request.getScheduledAt().isAfter(LocalDateTime.now())) {
            saved = executePayment(saved);
            saved = paymentRepository.save(saved);
        }

        audit("PAYMENT", saved.getId(), "CREATED", null, "ref=" + saved.getPaymentRef());
        return paymentMapper.toResponse(saved);
    }

    @Override
    public PaymentResponse getByRef(String ref) {
        return paymentMapper.toResponse(paymentRepository.findByPaymentRef(ref)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + ref)));
    }

    @Override
    public PaymentResponse getById(Long id) {
        return paymentMapper.toResponse(findById(id));
    }

    @Override
    public List<PaymentResponse> getByPayerAccount(Long accountId) {
        return paymentRepository.findByPayerAccountId(accountId).stream()
                .map(paymentMapper::toResponse).toList();
    }

    @Override
    public List<PaymentResponse> getScheduledByPayerAccount(Long accountId) {
        return paymentRepository.findByPayerAccountIdAndStatus(accountId, PaymentStatus.PENDING).stream()
                .filter(p -> p.getScheduledAt() != null)
                .map(paymentMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public PaymentResponse cancel(Long id) {
        Payment payment = findById(id);
        if (payment.getStatus() == PaymentStatus.COMPLETED || payment.getStatus() == PaymentStatus.CANCELLED) {
            throw new PaymentException("Cannot cancel payment in status: " + payment.getStatus());
        }
        payment.setStatus(PaymentStatus.CANCELLED);
        audit("PAYMENT", id, "CANCELLED", null, "ref=" + payment.getPaymentRef());
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    @Override
    @Transactional
    public void processScheduledPayments() {
        List<Payment> due = paymentRepository.findDueScheduledPayments(LocalDateTime.now());
        if (due.isEmpty()) return;

        log.info("Processing {} scheduled payments", due.size());
        for (Payment payment : due) {
            try {
                payment.setStatus(PaymentStatus.PROCESSING);
                paymentRepository.save(payment);

                executePayment(payment);
                payment.setStatus(PaymentStatus.COMPLETED);
                payment.setProcessedAt(LocalDateTime.now());

                if (payment.isRecurring() && payment.getRecurrencePattern() != null) {
                    LocalDate nextDate = calculateNextDate(LocalDate.now(), payment.getRecurrencePattern());
                    boolean withinEndDate = payment.getEndDate() == null || !nextDate.isAfter(payment.getEndDate());
                    if (withinEndDate) {
                        createNextOccurrence(payment, nextDate);
                    }
                }
            } catch (Exception e) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(e.getMessage());
                log.error("Scheduled payment {} failed: {}", payment.getPaymentRef(), e.getMessage());
                eventProducer.publishPaymentFailed(payment.getId(), payment.getPaymentRef(), e.getMessage());
            }
            paymentRepository.save(payment);
        }
    }

    private Payment executePayment(Payment payment) {
        if (payment.getPaymentType() == PaymentType.INTERNAL
                && payment.getPayeeAccountId() != null) {
            // The payment reference is the natural key: it is generated once
            // when the payment is created and does not change when the
            // scheduler retries a PROCESSING row after a restart. Deriving a
            // key from the amount and accounts instead would collapse two
            // genuine identical payments into one.
            transactionClient.transfer("payment-" + payment.getPaymentRef(), TransferRequest.builder()
                    .fromAccountId(payment.getPayerAccountId())
                    .toAccountId(payment.getPayeeAccountId())
                    .amount(payment.getAmount())
                    .currency(payment.getCurrency())
                    .description(payment.getDescription() != null ? payment.getDescription() : "Payment")
                    .build());
        }
        // External payment types (ACH, WIRE, SWIFT) would call integration-service — stub for now
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setProcessedAt(LocalDateTime.now());

        eventProducer.publishPaymentCompleted(payment.getId(), payment.getPaymentRef(),
                payment.getPayerAccountId(), payment.getPayeeAccountId(),
                payment.getAmount(), payment.getPaymentType().name());
        return payment;
    }

    private void createNextOccurrence(Payment original, LocalDate nextDate) {
        Payment next = Payment.builder()
                .paymentRef(UUID.randomUUID().toString())
                .payerAccountId(original.getPayerAccountId())
                .beneficiaryId(original.getBeneficiaryId())
                .payeeAccountId(original.getPayeeAccountId())
                .payeeExternalRef(original.getPayeeExternalRef())
                .paymentType(original.getPaymentType())
                .amount(original.getAmount())
                .currency(original.getCurrency())
                .description(original.getDescription())
                .recurring(true)
                .recurrencePattern(original.getRecurrencePattern())
                .endDate(original.getEndDate())
                .scheduledAt(nextDate.atStartOfDay())
                .status(PaymentStatus.PENDING)
                .build();
        paymentRepository.save(next);
        log.info("Created next recurring payment {} scheduled for {}", next.getPaymentRef(), nextDate);
    }

    private LocalDate calculateNextDate(LocalDate from, RecurrencePattern pattern) {
        return switch (pattern) {
            case DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case BIWEEKLY -> from.plusWeeks(2);
            case MONTHLY -> from.plusMonths(1);
            case ANNUALLY -> from.plusYears(1);
        };
    }

    private void validatePaymentRequest(CreatePaymentRequest request) {
        if (request.getPaymentType() == PaymentType.INTERNAL && request.getPayeeAccountId() == null) {
            throw new PaymentException("INTERNAL payment requires payeeAccountId");
        }
        if (request.isRecurring() && request.getRecurrencePattern() == null) {
            throw new PaymentException("Recurring payment requires recurrencePattern");
        }
        if (request.getPayerAccountId().equals(request.getPayeeAccountId())) {
            throw new PaymentException("Cannot pay to the same account");
        }
    }

    private Payment findById(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
    }

    private void audit(String entityType, Long entityId, String action, Long performedBy, String details) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .performedBy(performedBy)
                .details(details)
                .build());
    }
}
