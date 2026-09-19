package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A card statement was closed.
 *
 * <p>Consumer: {@code notification-service}, which tells the customer a
 * statement is available.
 *
 * <p>It never had. The producer published
 * {@code CREDIT_CARD_STATEMENT_GENERATED} and the consumer matched on
 * {@code STATEMENT_GENERATED}; both sides read correctly in isolation and the
 * notification had simply never been sent. The name is one shared constant
 * now. The event also gained {@code userId}, without which the consumer would
 * have returned early even once the name matched.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditCardStatementGenerated(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long cardId,
        Long userId,
        Long statementId,
        String statementDate) implements DomainEvent {

    public static final int VERSION = 1;

    public static CreditCardStatementGenerated of(Long cardId, Long userId, Long statementId,
                                                  String statementDate) {
        return new CreditCardStatementGenerated(EventMeta.newId(),
                EventTypes.CREDIT_CARD_STATEMENT_GENERATED, VERSION, EventMeta.now(),
                cardId, userId, statementId, statementDate);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(cardId);
    }
}
