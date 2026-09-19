package com.bankingplatform.fraud.kafka;

import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.PaymentFailed;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import com.bankingplatform.fraud.service.FraudDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the fraud rules from the event stream.
 *
 * <p>Both surviving rules had been dead. Transaction evaluation read a
 * {@code userId} the transaction events never carried, and the failed-payment
 * rule read a {@code payerAccountId} the failure event never carried — and
 * its {@code accountId != null} guard meant it silently did nothing rather
 * than failing. Repeated failed payments, the exact signal that rule exists
 * to catch, had never been counted once.
 *
 * <p>The listener on {@code credit-card-events} is deliberately gone. It read
 * an {@code accountId} that a card transaction does not have, so it had never
 * run either — but the honest fix is not to invent one. Its
 * {@code evaluateCreditCardPurchase} path ends in
 * {@code freezeAccount(accountId)}, which would freeze a customer's
 * <em>deposit</em> account because of a card purchase, and
 * {@code FraudAlert.accountId} is {@code NOT NULL} so an alert cannot even be
 * recorded against a card. Card fraud needs the alert to have a card subject
 * and needs a decision about what freezing a card means, which is a change to
 * the fraud model rather than to an event contract. Removed rather than left
 * looking like a working control; recorded in {@code docs/EVENTS.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformEventConsumer {

    private final FraudDetectionService fraudService;

    @KafkaListener(topics = Topics.TRANSACTION_EVENTS, groupId = "fraud-detection-service")
    public void onTransactionEvent(DomainEvent event) {
        if (event instanceof TransactionCreated e) {
            fraudService.evaluateTransaction(e.accountId(), e.userId(), e.amount(), e.transactionRef());
        } else if (event instanceof TransferCompleted e) {
            fraudService.evaluateTransaction(e.accountId(), e.userId(), e.amount(), e.transactionRef());
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "fraud-detection-service")
    public void onPaymentEvent(DomainEvent event) {
        if (event instanceof PaymentFailed e && e.payerAccountId() != null) {
            fraudService.evaluateFailedPayment(e.payerAccountId(), e.userId());
        }
    }
}
