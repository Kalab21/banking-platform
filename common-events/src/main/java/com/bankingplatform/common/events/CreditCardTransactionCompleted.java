package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A purchase, cash advance or payment was applied to a card.
 *
 * <p>Consumers: {@code statistics-service} (per-card totals) and
 * {@code user-service}, which raises the credit score when the transaction is
 * a payment. {@code user-service} needs {@code userId}, which the event
 * already carried nowhere — it read the field and returned when it was null,
 * so no card payment had ever moved a credit score.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditCardTransactionCompleted(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long cardId,
        Long userId,
        String transactionRef,
        String transactionType,
        BigDecimal amount,
        BigDecimal availableCredit) implements DomainEvent {

    public static final int VERSION = 1;

    public static CreditCardTransactionCompleted of(Long cardId, Long userId, String transactionRef,
                                                    String transactionType, BigDecimal amount,
                                                    BigDecimal availableCredit) {
        return new CreditCardTransactionCompleted(EventMeta.newId(),
                EventTypes.CREDIT_CARD_TRANSACTION_COMPLETED, VERSION, EventMeta.now(),
                cardId, userId, transactionRef, transactionType, amount, availableCredit);
    }

    /** Keyed by card: one card's transactions must stay in order. */
    @Override
    public String partitionKey() {
        return String.valueOf(cardId);
    }
}
