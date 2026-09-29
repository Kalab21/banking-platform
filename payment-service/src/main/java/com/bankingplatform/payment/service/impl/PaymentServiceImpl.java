package com.bankingplatform.payment.service.impl;

import com.bankingplatform.payment.idempotency.PaymentOutcomeClassifier;
import com.bankingplatform.payment.dto.WithdrawRequest;

import com.bankingplatform.common.security.CallerContext;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.common.security.Role;
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
            // A recurring payment that runs now still recurs. Only the
            // scheduler used to create the next occurrence, so a series whose
            // first payment was immediate stopped after one.
            scheduleNextOccurrence(saved);
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
        Payment payment = paymentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
        // PROCESSING means the scheduler has claimed it and may already have
        // moved the money; cancelling then would report a payment as stopped
        // that went through.
        if (payment.getStatus() == PaymentStatus.COMPLETED || payment.getStatus() == PaymentStatus.CANCELLED
                || payment.getStatus() == PaymentStatus.PROCESSING) {
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
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            // Cancelled, or finished by another worker, since it was claimed.
            log.info("Payment {} is {} rather than PROCESSING; not executing it",
                    paymentId, payment.getStatus());
            return;
        }

        // Resolved once, before the attempt, and reused by whichever event is
        // published. Doing it in the catch block meant that when
        // account-service was the thing that had failed, every failed payment
        // paid a full Feign read timeout again on the way out.
        Long payerUserId = ownerOf(payment.getPayerAccountId());
        try {
            // A timer has no caller. The transfer runs as the customer who owns
            // the paying account -- the one who scheduled it -- so
            // transaction-service authorizes it the way it would that
            // customer's own request, rather than refusing an anonymous one.
            if (payerUserId == null) {
                throw new PaymentException("The paying account's owner could not be resolved");
            }
            CallerContext.runAs(new CallerIdentity(payerUserId, "scheduled-payment", Role.CUSTOMER),
                    () -> executePayment(payment, payerUserId));
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setProcessedAt(LocalDateTime.now());

            // Created in the same transaction as the payment that begets it.
            // Two workers processing one payment would otherwise each schedule
            // the next occurrence, and the customer would be billed twice next
            // month -- which the transfer's idempotency key does not protect
            // against, because the two occurrences are genuinely different
            // payments.
            scheduleNextOccurrence(payment);
        } catch (Exception e) {
            boolean declined = e instanceof RuntimeException runtime
                    && new PaymentOutcomeClassifier().movedNoMoney(runtime);
            payment.setStatus(PaymentStatus.FAILED);
            // A decline moved nothing; anything else may have. The status stays
            // FAILED, but the reason says which, so no one reads an unconfirmed
            // debit as a clean failure.
            String reason = declined ? String.valueOf(e.getMessage())
                    : "Outcome unconfirmed; check the account before paying again: " + e.getMessage();
            // The column holds 500 characters; a Feign message carrying a
            // response body can be longer, and failing the save would leave
            // the payment stuck in PROCESSING.
            payment.setFailureReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
            if (declined) {
                // One declined month is not the end of a recurring series. It
                // used to be: the next occurrence was only created on success.
                scheduleNextOccurrence(payment);
            }
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
        if (payment.getPaymentType() != PaymentType.INTERNAL) {
            // Money leaving the bank. The rail is simulated, but the payer's
            // balance is not: these used to be marked COMPLETED, and announced
            // as paid, without the account being debited at all.
            transactionClient.withdraw("payment-" + payment.getPaymentRef(), WithdrawRequest.builder()
                    .accountId(payment.getPayerAccountId())
                    .amount(payment.getAmount())
                    .description(payment.getDescription() != null ? payment.getDescription()
                            : "Payment — " + payment.getPaymentType())
                    .build());
        }
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
            return accountClient.getAccountInternal(accountId).getUserId();
        } catch (Exception e) {
            log.warn("Could not resolve the owner of account {} for a payment event: {}",
                    accountId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The next payment in a recurring series, dated from when this one was due.
     *
     * Dating from "today" let the series drift by however late the scheduler
     * ran. A date that is already past (a long outage) falls back to today, so
     * a missed run is not replayed month after month in one go.
     */
    private void scheduleNextOccurrence(Payment payment) {
        if (!payment.isRecurring() || payment.getRecurrencePattern() == null) return;
        LocalDate due = payment.getScheduledAt() != null ? payment.getScheduledAt().toLocalDate() : LocalDate.now();
        LocalDate nextDate = calculateNextDate(due, payment.getRecurrencePattern());
        if (!nextDate.isAfter(LocalDate.now())) {
            nextDate = calculateNextDate(LocalDate.now(), payment.getRecurrencePattern());
        }
        if (payment.getEndDate() != null && nextDate.isAfter(payment.getEndDate())) return;
        createNextOccurrence(payment, nextDate);
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
        if (request.getPaymentType() == PaymentType.CREDIT_CARD_PAYMENT) {
            // A card payment has to reduce the card's balance as well as debit
            // the account, and only credit-card-service can do the first.
            throw new PaymentException("Pay a credit card from the card itself: POST /api/credit-cards/{id}/payment");
        }
        if (request.getPaymentType() == PaymentType.INTERNAL && request.getPayeeAccountId() == null) {
            throw new PaymentException("INTERNAL payment requires payeeAccountId");
        }
        // Money that leaves Northbank has to be going somewhere. Only INTERNAL
        // payments were checked for a payee, so a WIRE or BILL payment naming
        // none debited the payer and recorded nobody as receiving it.
        if (request.getPaymentType() != PaymentType.INTERNAL
                && request.getBeneficiaryId() == null
                && (request.getPayeeExternalRef() == null || request.getPayeeExternalRef().isBlank())) {
            throw new PaymentException("A payment outside Northbank needs a payee: a saved beneficiary or an external reference");
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
