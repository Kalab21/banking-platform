package com.bankingplatform.transaction.service;

import com.bankingplatform.transaction.client.AccountClient;
import com.bankingplatform.transaction.dto.MovementStatusResponse;
import com.bankingplatform.transaction.model.TransferAttempt;
import com.bankingplatform.transaction.model.TransferAttemptStatus;
import com.bankingplatform.transaction.repository.TransferAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Establishes what actually happened to a transfer that did not finish.
 *
 * <p>A transfer has two legs in another service. When one of them fails or
 * times out, this service knows what it intended and not what landed. The
 * legs are keyed, so the truth is knowable: account-service can be asked what
 * became of each key, and it answers from the same record that moved the
 * balance.
 *
 * <p><b>It reports; it does not repair.</b> Crediting the destination of a
 * half-applied transfer, or reversing the debit, is a decision about someone's
 * money — which of the two accounts should be made whole, and whether the
 * customer is told. The platform's stated position is that partial outcomes
 * are reconciled rather than automatically retried, and inventing a
 * compensation policy here would quietly overrule that. So this turns "we do
 * not know" into "here is exactly what happened, on these two accounts, for
 * this amount", and stops.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransferReconciler {

    private static final List<TransferAttemptStatus> UNSETTLED = List.of(
            TransferAttemptStatus.STARTED,
            TransferAttemptStatus.DEBITED,
            TransferAttemptStatus.CREDIT_FAILED);

    private final TransferAttemptRepository attempts;
    private final TransferAttemptRecorder recorder;
    private final AccountClient accountClient;

    /**
     * How long an attempt is left alone before it counts as stuck.
     *
     * <p>A transfer takes milliseconds. One still unsettled minutes later has
     * stopped, not slowed — but the margin keeps a transfer that is merely in
     * flight out of the reconciliation list.
     */
    @Value("${transfers.reconciliation.after-minutes:5}")
    private int afterMinutes;

    @Value("${transfers.reconciliation.batch-size:50}")
    private int batchSize;

    /** Attempts an operator may have to act on. */
    @Transactional(readOnly = true)
    public List<TransferAttempt> unsettled() {
        return attempts.findUnsettled(UNSETTLED, LocalDateTime.now().minusMinutes(afterMinutes));
    }

    /**
     * Asks account-service what became of each leg, and records the answer.
     *
     * @return how many attempts were resolved
     */
    public int reconcile() {
        List<TransferAttempt> stuck = unsettled();
        if (stuck.isEmpty()) {
            return 0;
        }

        int resolved = 0;
        for (TransferAttempt attempt : stuck.stream().limit(batchSize).toList()) {
            try {
                boolean debitApplied = applied("txn-" + attempt.getDebitRef());
                boolean creditApplied = applied("txn-" + attempt.getCreditRef());

                recorder.reconciled(attempt.getId(), debitApplied, creditApplied,
                        describe(debitApplied, creditApplied));
                resolved++;

                if (debitApplied && !creditApplied) {
                    // The one that needs a person. Logged at error with both
                    // accounts and the amount, because the balance is wrong
                    // now and nothing here is going to fix it.
                    log.error("Transfer {} is half applied: account {} was debited {} {} and "
                                    + "account {} was never credited. This needs a decision, and "
                                    + "the platform will not make it automatically.",
                            attempt.getDebitRef(), attempt.getFromAccountId(), attempt.getAmount(),
                            attempt.getCurrency(), attempt.getToAccountId());
                }
            } catch (RuntimeException e) {
                // account-service being unreachable is why this attempt is
                // unsettled in the first place. Leave it and try again.
                log.warn("Could not reconcile transfer {}: {}", attempt.getDebitRef(), e.getMessage());
            }
        }
        return resolved;
    }

    private boolean applied(String key) {
        MovementStatusResponse status = accountClient.movementStatus(key);
        return status != null && status.isApplied();
    }

    private static String describe(boolean debitApplied, boolean creditApplied) {
        if (debitApplied && creditApplied) {
            return "Both legs applied; the transfer completed despite the error reported to the caller.";
        }
        if (debitApplied) {
            return "The debit applied and the credit did not. The source account is short.";
        }
        if (creditApplied) {
            return "The credit applied and the debit did not. The destination account is over.";
        }
        return "Neither leg applied. Nothing moved and the transfer may be reissued.";
    }
}
