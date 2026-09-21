package com.bankingplatform.transaction.scheduler;

import com.bankingplatform.transaction.service.TransferReconciler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks for transfers that did not finish, and works out what happened.
 *
 * <p>Runs on a timer rather than from a failure handler, because the failures
 * worth catching are the ones where this service stopped running. A process
 * that dies between the debit and the credit leaves nothing to trigger a
 * handler; only a later pass over the durable record can find it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransferReconciliationJob {

    private final TransferReconciler reconciler;

    @Scheduled(fixedDelayString = "${transfers.reconciliation.interval:300000}")
    public void reconcile() {
        try {
            int resolved = reconciler.reconcile();
            if (resolved > 0) {
                log.info("Reconciled {} unsettled transfers", resolved);
            }
        } catch (RuntimeException e) {
            // Nothing that happens in one pass may stop the next one running.
            log.error("Transfer reconciliation pass failed: {}", e.toString());
        }
    }
}
