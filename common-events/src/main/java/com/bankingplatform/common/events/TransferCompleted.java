package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Both legs of an internal transfer were applied.
 *
 * <p>Consumers: {@code notification-service}, {@code statistics-service},
 * {@code fraud-detection-service} — all of which also handle
 * {@link TransactionCreated} and read the same fields from both, so this
 * carries the same owner and amount rather than a different shape for the
 * same question.
 *
 * <p>{@code accountId} is the <em>source</em> account, and {@code userId} its
 * owner, which is the account the money left and the customer who authorised
 * it. Those are the two facts every consumer of this event actually wants; the
 * destination is carried separately for completeness.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompleted(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String transactionRef,
        String creditRef,
        Long accountId,
        Long userId,
        Long toAccountId,
        BigDecimal amount) implements DomainEvent {

    public static final int VERSION = 1;

    public static TransferCompleted of(String debitRef, String creditRef, Long fromAccountId,
                                       Long fromUserId, Long toAccountId, BigDecimal amount) {
        return new TransferCompleted(EventMeta.newId(), EventTypes.TRANSFER_COMPLETED, VERSION,
                EventMeta.now(), debitRef, creditRef, fromAccountId, fromUserId, toAccountId, amount);
    }

    /** The source account, so a transfer is ordered with that account's other events. */
    @Override
    public String partitionKey() {
        return String.valueOf(accountId);
    }
}
