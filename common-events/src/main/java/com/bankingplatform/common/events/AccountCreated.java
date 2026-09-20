package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * An account was opened.
 *
 * <p>Consumers: {@code notification-service} (tells the customer),
 * {@code statistics-service} (counts it). Both need only the owning user; the
 * notification names the account type, not the number.
 *
 * <p><b>The account number is deliberately absent.</b> The previous event
 * carried the full number, and nothing consumed it. A bank account number in
 * an event is a copy of customer data in every consumer's log, in the broker's
 * on-disk segments and in anything that later tails the topic — for a field no
 * feature reads. If a consumer ever needs to show an account, it should carry
 * the last four digits and no more.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountCreated(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long accountId,
        Long userId,
        String accountType) implements DomainEvent {

    public static final int VERSION = 1;

    public static AccountCreated of(Long accountId, Long userId, String accountType) {
        return new AccountCreated(EventMeta.newId(), EventTypes.ACCOUNT_CREATED, VERSION,
                EventMeta.now(), accountId, userId, accountType);
    }

    /** Ordered per account: two events about one account must not overtake each other. */
    @Override
    public String partitionKey() {
        return String.valueOf(accountId);
    }
}
