package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An account went into its overdraft.
 *
 * <p>Consumer: {@code notification-service}, which tells the customer.
 *
 * <p>That notification had never fired. {@code account-service} published this
 * on {@code account-events} under the field {@code overdraftAmount}, while the
 * consumer listened for it on {@code transaction-events} and read a field
 * called {@code amount} — wrong topic and wrong field, each of which alone was
 * enough. The topic is now {@link Topics#ACCOUNT_EVENTS} on both sides and the
 * field has one name.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OverdraftTriggered(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long accountId,
        Long userId,
        BigDecimal overdraftAmount) implements DomainEvent {

    public static final int VERSION = 1;

    public static OverdraftTriggered of(Long accountId, Long userId, BigDecimal overdraftAmount) {
        return new OverdraftTriggered(EventMeta.newId(), EventTypes.OVERDRAFT_TRIGGERED, VERSION,
                EventMeta.now(), accountId, userId, overdraftAmount);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(accountId);
    }
}
