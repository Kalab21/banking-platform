package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A credit card was issued.
 *
 * <p>Consumers: {@code notification-service} (tells the customer, naming the
 * card) and {@code statistics-service} (counts it).
 *
 * <p><b>{@code last4}, never the card number.</b> The producer sent no number
 * at all while the notification read {@code cardNumber} and called
 * {@code substring(length - 4)} on it — so every issuance notification threw
 * {@link StringIndexOutOfBoundsException} on the empty default and was
 * swallowed by the consumer's catch. The fix is not to start publishing the
 * PAN: a card number on a Kafka topic is a copy of it in every consumer's
 * logs and in the broker's segments, and the last four digits are all the
 * message needs to say which card it means.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditCardCreated(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long cardId,
        Long userId,
        String cardType,
        String last4) implements DomainEvent {

    public static final int VERSION = 1;

    public static CreditCardCreated of(Long cardId, Long userId, String cardType, String last4) {
        return new CreditCardCreated(EventMeta.newId(), EventTypes.CREDIT_CARD_CREATED, VERSION,
                EventMeta.now(), cardId, userId, cardType, last4);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(cardId);
    }
}
