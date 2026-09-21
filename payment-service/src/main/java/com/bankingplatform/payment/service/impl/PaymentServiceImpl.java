package com.bankingplatform.payment.service.impl;

import com.bankingplatform.common.security.CallerContext;
import com.bankingplatform.payment.client.AccountClient;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final AccountClient accountClient;

    /**
     * How long a payment may sit in PROCESSING before another worker assumes
     * the one that claimed it is gone. Long enough that a slow payment is not
     * taken away from a worker still running it.
     */
    @Value("${payments.scheduler.stalled-after-minutes:15}")
    private int stalledAfterMinutes;

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

        audit("PAYMENT", saved.getId(), "CREATED", "ref=" + saved.getPaymentRef());
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
        audit("PAYMENT", id, "CANCELLED", "ref=" + payment.getPaymentRef());
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    /**
     * Takes ownership of due payments, and of any a dead worker left behind.
     *
     * <p>Two steps, both inside this one transaction. The rows are locked
     * with {@code SKIP LOCKED}, so a second replica takes different work
     * rather than the same work or waiting for it; then their status is
     * changed, which is the half of the claim that outlives this
     * transaction. Locking alone would not do: each payment is processed in
     * a transaction of its own, and the lock is gone the moment the claim
     * commits.
     *
     * <p>Stalled payments are picked up in the same pass. A claim survives
     * the worker that made it, which is the point of writing it down and
     * also the risk -- without this, a payment claimed by a process that then
     * died would sit in PROCESSING forever. Re-running one is safe: the
     * transfer carries an idempotency key derived from the payment
     * reference, so a second attempt is a replay rather than a second
     * movement of money.
     */
    @Override
    @Transactional
    public List<Long> claimScheduledPayments(int limit) {
        List<Long> ids = new ArrayList<>(
                paymentRepository.lockDueScheduledPayments(LocalDateTime.now(), limit));

        int remaining = limit - ids.size();
        if (remaining > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(stalledAfterMinutes);
            List<Long> stalled = paymentRepository.lockStalledPayments(cutoff, remaining);
            if (!stalled.isEmpty()) {
                log.warn("Re-claiming {} payments left PROCESSING for more than {} minutes",
                        stalled.size(), stalledAfterMinutes);
                ids.addAll(stalled);
            }
        }

        if (ids.isEmpty()) {
            return List.of();
        }
        paymentRepository.claim(ids, LocalDateTime.now());
        return ids;
    }

    /**
     * Processes one payment this worker has already claimed.
     *
     * <p>Its own transaction, so a payment that fails takes nothing else down
     * with it. The previous version ran the whole batch in one transaction:
     * an exception escaping the per-payment catch rolled back the bookkeeping
     * for every payment already handled, while the transfers those payments
     * had performed in another service stood.
     */
    @Override
    @Transactional
    public void processClaimedPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));

        // Resolved once, before the attempt, and reused by whichever event is
        // published. Doing it in the catch block meant that when
        // account-service was the thing that had failed, every failed payment
        // paid a full Feign read timeout again on the way out.
        Long payerUserId = ownerOf(payment.getPayerAccountId());
        try {
            executePayment(payment, payerUserId);
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setProcessedAt(LocalDateTime.now());

            if (payment.isRecurring() && payment.getRecurrencePattern() != null) {
                LocalDate nextDate = calculateNextDate(LocalDate.now(), payment.getRecurrencePattern());
                boolean withinEndDate = payment.getEndDate() == null || !nextDate.isAfter(payment.getEndDate());
                if (withinEndDate) {
                    // Created in the same transaction as the payment that
                    // begets it. Two workers processing one payment would
                    // otherwise each schedule the next occurrence, and the
                    // customer would be billed twice next month -- which the
                    // transfer's idempotency key does not protect against,
                    // because the two occurrences are genuinely different
                    // payments.
                    createNextOccurrence(payment, nextDate);
                }
            }
        } catch (Exception e) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(e.getMessage());
            log.error("Scheduled payment {} failed: {}", payment.getPaymentRef(), e.getMessage());
            eventProducer.publishPaymentFailed(payment.getId(), payment.getPaymentRef(),
                    payment.getPayerAccountId(), payerUserId);
        }
        paymentRepository.save(payment);
    }

    private Payment executePayment(Payment payment) {
        return executePayment(payment, ownerOf(payment.getPayerAccountId()));
    }

    private Payment executePayment(Payment payment, Long payerUserId) {
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
                payment.getPayerAccountId(), payerUserId,
                payment.getPayeeAccountId(), payment.getAmount(), payment.getPaymentType().name());
        return payment;
    }

    /**
     * The user who owns the paying account.
     *
     * <p>A payment belongs to an account, not to a user, so the owner has to
     * be resolved from account-service — the same question this service's
     * authorization already asks of the same service.
     *
     * <p>Never fails the payment. The money has already moved by the time
     * this is called, and an event that cannot name its user is a smaller
     * problem than a completed payment reported as failed. A null owner means
     * the notification is skipped, which is what happened for every payment
     * before this field existed at all.
     */
    private Long ownerOf(Long accountId) {
        if (accountId == null) {
            return null;
        }
        try {
            return accountClient.getAccountById(accountId).getUserId();
        } catch (Exception e) {
            log.warn("Could not resolve the owner of account {} for a payment event: {}",
                    accountId, e.getClass().getSimpleName());
            return null;
        }
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

    /**
     * Records who did this, not only what was done.
     *
     * <p>The actor comes from the request rather than from an argument.
     * Several call sites used to pass the <em>subject</em> of the change --
     * the account holder, the applicant -- which reads correctly right up
     * until a member of staff acts on a customer's behalf, and then the audit
     * row names the customer as having done it themselves.
     *
     * <p>{@code actorType} is always set. A scheduled job or a Kafka listener
     * has no caller and is recorded as {@code SYSTEM}, so a null
     * {@code performedBy} beside it means "no user was involved" rather than
     * "the attribution was lost".
     */
    private void audit(String entityType, Long entityId, String action, String details) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .performedBy(CallerContext.userId().orElse(null))
                .actorType(CallerContext.actor())
                .details(details)
                .build());
    }
}
