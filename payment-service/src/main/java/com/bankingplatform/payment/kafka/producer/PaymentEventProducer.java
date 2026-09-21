package com.bankingplatform.payment.kafka.producer;

import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.PaymentCompleted;
import com.bankingplatform.common.events.PaymentFailed;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Publishes the outcome of a payment.
 *
 * <p>Both events now name the paying account's owner. Neither did:
 * {@code notification-service} reads {@code userId} and returned immediately,
 * so neither the payment receipt nor the failure notice had ever been sent,
 * and {@code fraud-detection-service} reads {@code payerAccountId} from the
 * failure — which the failure event did not carry either — so repeated failed
 * payments, the signal that rule exists to catch, were never counted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    private final OutboxPublisher outbox;

    public void publishPaymentCompleted(Long paymentId, String ref, Long payerAccountId, Long userId,
                                        Long payeeAccountId, BigDecimal amount, String type) {
        send(PaymentCompleted.of(paymentId, ref, payerAccountId, userId, payeeAccountId, amount, type));
        log.info("Published PAYMENT_COMPLETED: ref={}, amount={}", ref, amount);
    }

    public void publishPaymentFailed(Long paymentId, String ref, Long payerAccountId, Long userId) {
        send(PaymentFailed.of(paymentId, ref, payerAccountId, userId));
        log.warn("Published PAYMENT_FAILED: ref={}", ref);
    }

    /**
     * Records the event in the outbox, in the caller's transaction.
     *
     * <p>A payment that has moved money and a receipt that was never sent are
     * the same bug as a lost balance update, with a customer on the end of
     * it. The failure event matters as much: repeated failed payments are the
     * signal one of the fraud rules exists for, and a rule that never fires
     * because the event was dropped is indistinguishable from a rule that
     * found nothing.
     */
    private void send(DomainEvent event) {
        outbox.publish(Topics.PAYMENT_EVENTS, event);
    }
}
