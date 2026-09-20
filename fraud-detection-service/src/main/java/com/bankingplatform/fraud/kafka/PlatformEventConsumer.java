package com.bankingplatform.fraud.kafka;

import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.PaymentFailed;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import com.bankingplatform.fraud.service.FraudDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 *
 * <p>Every handler claims the event id in the same transaction as its effect,
 * so a redelivery — which retry now makes ordinary — does the work once. The
 * claim is per handler rather than per service, because several handlers here
 * consume the same topic and each has to act on an event exactly once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformEventConsumer {

    private static final String TRANSACTION_CONSUMER = "fraud-detection-service:transaction";
    private static final String PAYMENT_CONSUMER = "fraud-detection-service:payment";

    private final ProcessedEventGuard processedEvents;
    private final FraudDetectionService fraudService;

    @KafkaListener(topics = Topics.TRANSACTION_EVENTS, groupId = "fraud-detection-service")
    @Transactional
    public void onTransactionEvent(DomainEvent event) {
        // The account is still checked for null. The type makes the field
        // required in Java, not in the JSON: a record written by an older
        // producer during a rolling deploy deserializes with a null there.
        // fraud_alerts.account_id and fraud_rules_audit.account_id are both
        // NOT NULL, and a null account would collapse every such event onto
        // one Redis velocity key, mixing unrelated customers into one counter.
        //
        // The claim sits below the type check on purpose: an event this
        // service does not act on should leave no trace, and UnknownEvent in
        // particular is documented as no side effect and no error. Claiming
        // first would write a processed-event row for every record on the topic.
        if (event instanceof TransactionCreated e && e.accountId() != null) {
            if (!processedEvents.claim(TRANSACTION_CONSUMER, e.eventId())) {
                return;
            }
            fraudService.evaluateTransaction(e.accountId(), e.userId(), e.amount(),
                    e.transactionRef(), e.eventId());
        } else if (event instanceof TransferCompleted e && e.accountId() != null) {
            if (!processedEvents.claim(TRANSACTION_CONSUMER, e.eventId())) {
                return;
            }
            fraudService.evaluateTransaction(e.accountId(), e.userId(), e.amount(),
                    e.transactionRef(), e.eventId());
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "fraud-detection-service")
    @Transactional
    public void onPaymentEvent(DomainEvent event) {
        if (event instanceof PaymentFailed e && e.payerAccountId() != null) {
            if (!processedEvents.claim(PAYMENT_CONSUMER, e.eventId())) {
                return;
            }
            fraudService.evaluateFailedPayment(e.payerAccountId(), e.userId(), e.eventId());
        }
    }
}
