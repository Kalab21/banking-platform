package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A deposit or withdrawal was recorded against an account.
 *
 * <p>Consumers: {@code notification-service} (large-transaction alert),
 * {@code statistics-service} (per-user totals), {@code fraud-detection-service}
 * (velocity checks).
 *
 * <p><b>{@code userId} was missing.</b> The producer sent {@code accountId} and
 * never the owner, while all three consumers read {@code userId} first and
 * returned early when it was null. So the large-transaction notification never
 * fired, per-user statistics were attributed to no one, and fraud evaluation
 * ran with a null user. It is required here, so the producer cannot omit it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionCreated(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long transactionId,
        String transactionRef,
        Long accountId,
        Long userId,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter) implements DomainEvent {

    public static final int VERSION = 1;

    public static TransactionCreated of(Long transactionId, String transactionRef, Long accountId,
                                        Long userId, String type, BigDecimal amount,
                                        BigDecimal balanceAfter) {
        return new TransactionCreated(EventMeta.newId(), EventTypes.TRANSACTION_CREATED, VERSION,
                EventMeta.now(), transactionId, transactionRef, accountId, userId, type, amount, balanceAfter);
    }

    /**
     * Keyed by account, not by transaction reference.
     *
     * <p>The reference was the key before, which is unique per event and so
     * spread one account's history across every partition. Two transactions on
     * the same account could then be consumed out of order, and a balance-derived
     * consumer could see the later one first.
     */
    @Override
    public String partitionKey() {
        return String.valueOf(accountId);
    }
}
