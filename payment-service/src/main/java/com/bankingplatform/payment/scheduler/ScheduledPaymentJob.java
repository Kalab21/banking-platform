package com.bankingplatform.payment.scheduler;

import com.bankingplatform.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives the scheduled-payment worker.
 *
 * <p>Claim first, then process one payment per transaction. Two things follow
 * from that split, and both are the point of it:
 *
 * <ul>
 *   <li><b>Two replicas do not process the same payment.</b> The claim locks
 *       rows with {@code SKIP LOCKED} and changes their status, so a second
 *       replica picks up different work rather than the same work.</li>
 *   <li><b>One bad payment does not undo the batch.</b> The previous version
 *       ran every due payment in a single transaction, so a failure that
 *       escaped the per-payment catch rolled back the bookkeeping for every
 *       payment already handled -- while the transfers those payments had
 *       performed, in another service, stood.</li>
 * </ul>
 *
 * <p>The orchestration lives here rather than inside the service because each
 * call has to cross the proxy to get its own transaction. A loop inside one
 * {@code @Transactional} method is one transaction however it is written.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledPaymentJob {

    private final PaymentService paymentService;

    /** How many payments one worker takes per tick. */
    @Value("${payments.scheduler.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelay = 60000)
    public void processScheduledPayments() {
        List<Long> claimed;
        try {
            claimed = paymentService.claimScheduledPayments(batchSize);
        } catch (RuntimeException e) {
            // A failed claim means this tick does nothing. The next one tries
            // again, and nothing was claimed, so no payment is stranded.
            log.error("Could not claim scheduled payments: {}", e.getMessage());
            return;
        }

        if (claimed.isEmpty()) {
            return;
        }

        log.info("Processing {} scheduled payments", claimed.size());
        for (Long paymentId : claimed) {
            try {
                paymentService.processClaimedPayment(paymentId);
            } catch (RuntimeException e) {
                // Already claimed, so it will not be picked up again until the
                // stalled-payment grace period passes -- which is the correct
                // outcome for a payment whose fate is unclear, and why the
                // failure is logged at error rather than swallowed.
                log.error("Scheduled payment {} could not be processed: {}",
                        paymentId, e.getMessage());
            }
        }
    }
}
