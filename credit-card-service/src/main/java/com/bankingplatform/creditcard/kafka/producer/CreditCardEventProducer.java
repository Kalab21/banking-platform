package com.bankingplatform.creditcard.kafka.producer;

import com.bankingplatform.common.events.CreditCardCreated;
import com.bankingplatform.common.events.CreditCardStatementGenerated;
import com.bankingplatform.common.events.CreditCardTransactionCompleted;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Publishes what happened to a card.
 *
 * <p>Two contract breaks are fixed here. The statement event was published as
 * {@code CREDIT_CARD_STATEMENT_GENERATED} and consumed as
 * {@code STATEMENT_GENERATED}, so the statement notification had never been
 * sent; the name is one shared constant now. And every event on this topic
 * gained {@code userId}, without which the notification and credit-score
 * consumers return before doing anything.
 *
 * <p>The issuance event carries {@code last4} and never the card number. The
 * notification wants to name the card; four digits do that, and a PAN on a
 * Kafka topic would be a copy of it in every consumer's logs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CreditCardEventProducer {

    private final OutboxPublisher outbox;

    public void publishTransactionCompleted(Long cardId, Long userId, String ref, String type,
                                            BigDecimal amount, BigDecimal availableCredit) {
        send(CreditCardTransactionCompleted.of(cardId, userId, ref, type, amount, availableCredit));
        log.info("Published CREDIT_CARD_TRANSACTION_COMPLETED: ref={}, type={}, amount={}", ref, type, amount);
    }

    public void publishCardCreated(Long cardId, Long userId, String cardType, String last4) {
        send(CreditCardCreated.of(cardId, userId, cardType, last4));
        log.info("Published CREDIT_CARD_CREATED: cardId={}, userId={}", cardId, userId);
    }

    public void publishStatementGenerated(Long cardId, Long userId, Long statementId, String statementDate) {
        send(CreditCardStatementGenerated.of(cardId, userId, statementId, statementDate));
        log.info("Published CREDIT_CARD_STATEMENT_GENERATED: cardId={}, statementId={}", cardId, statementId);
    }

    /**
     * Records the event in the outbox, in the caller's transaction.
     *
     * <p>Card transactions are the other route into a credit score, and the
     * statement event is the only thing that tells a customer a statement
     * exists. Both used to depend on a send whose failure was logged and
     * forgotten.
     */
    private void send(DomainEvent event) {
        outbox.publish(Topics.CREDIT_CARD_EVENTS, event);
    }
}
