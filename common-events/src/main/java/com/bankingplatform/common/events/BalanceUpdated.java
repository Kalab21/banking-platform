package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An account's balance changed.
 *
 * <p>No consumer acts on this today. It is kept because it is the natural
 * record of a balance change and is cheap to publish, but it is listed as
 * unconsumed in {@code docs/EVENTS.md} rather than described as if something
 * depended on it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceUpdated(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long accountId,
        Long userId,
        BigDecimal newBalance,
        String operation) implements DomainEvent {

    public static final int VERSION = 1;

    public static BalanceUpdated of(Long accountId, Long userId, BigDecimal newBalance, String operation) {
        return new BalanceUpdated(EventMeta.newId(), EventTypes.BALANCE_UPDATED, VERSION,
                EventMeta.now(), accountId, userId, newBalance, operation);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(accountId);
    }
}
