package com.bankingplatform.transaction.service;

import com.bankingplatform.transaction.dto.TransferRequest;
import com.bankingplatform.transaction.model.TransferAttempt;
import com.bankingplatform.transaction.model.TransferAttemptStatus;
import com.bankingplatform.transaction.repository.TransferAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records how far a transfer got, outside the transfer's own transaction.
 *
 * <p>Every method here commits on its own. That is the entire point: the case
 * worth recording is the one where the transfer fails, and a record written
 * inside the transfer would be rolled back by the same failure that makes it
 * worth having. A transfer that debited the source and could not credit the
 * destination used to leave no trace in this service at all.
 *
 * <p>Separate bean rather than a method on the service, because
 * {@code REQUIRES_NEW} on a method the service calls on itself does not go
 * through the proxy and would quietly share the caller's transaction — which
 * is exactly the behaviour being avoided.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransferAttemptRecorder {

    private final TransferAttemptRepository attempts;

    /**
     * Writes the intent before the first leg is attempted.
     *
     * @return the attempt id, so later stages can find it without re-reading
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long started(String debitRef, String creditRef, TransferRequest request, String currency) {
        TransferAttempt attempt = attempts.save(TransferAttempt.builder()
                .debitRef(debitRef)
                .creditRef(creditRef)
                .fromAccountId(request.getFromAccountId())
                .toAccountId(request.getToAccountId())
                .amount(request.getAmount())
                .currency(currency)
                .status(TransferAttemptStatus.STARTED)
                .build());
        return attempt.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void debited(Long attemptId) {
        update(attemptId, TransferAttemptStatus.DEBITED, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completed(Long attemptId) {
        update(attemptId, TransferAttemptStatus.COMPLETED, null);
    }

    /**
     * The debit applied and the credit did not.
     *
     * <p>Committed separately so it outlives the rollback of the transfer
     * that produced it. Without this the platform's only record of a
     * half-applied transfer is a balance that is short and an idempotency row
     * marked UNKNOWN, neither of which names the two accounts or the amount.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void creditFailed(Long attemptId, String reason) {
        update(attemptId, TransferAttemptStatus.CREDIT_FAILED, truncate(reason));
        log.error("Transfer attempt {} left the source debited and the destination uncredited. "
                + "It is recorded for reconciliation and will not be retried automatically.", attemptId);
    }

    /** Records what reconciliation established about each leg. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconciled(Long attemptId, boolean debitApplied, boolean creditApplied, String note) {
        attempts.findById(attemptId).ifPresent(attempt -> {
            attempt.setDebitApplied(debitApplied);
            attempt.setCreditApplied(creditApplied);
            attempt.setReconciledAt(java.time.LocalDateTime.now());
            attempt.setStatus(TransferAttemptStatus.RECONCILED);
            attempt.setNote(truncate(note));
            attempts.save(attempt);
        });
    }

    private void update(Long attemptId, TransferAttemptStatus status, String note) {
        attempts.findById(attemptId).ifPresent(attempt -> {
            attempt.setStatus(status);
            if (note != null) {
                attempt.setNote(note);
            }
            attempts.save(attempt);
        });
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
