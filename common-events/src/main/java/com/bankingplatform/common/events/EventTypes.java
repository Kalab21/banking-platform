package com.bankingplatform.common.events;

/**
 * The event type names, as they appear on the wire.
 *
 * <p>Compile-time constants rather than string literals, because the whole
 * class of bug this module exists to remove is a producer and a consumer
 * disagreeing about a spelling. {@code credit-card-service} published
 * {@code CREDIT_CARD_STATEMENT_GENERATED} while {@code notification-service}
 * matched on {@code STATEMENT_GENERATED}; both sides looked right in
 * isolation and the statement notification had simply never worked. Two
 * references to one constant cannot drift.
 */
public final class EventTypes {

    private EventTypes() {
    }

    // account-events
    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";
    public static final String BALANCE_UPDATED = "BALANCE_UPDATED";
    public static final String OVERDRAFT_TRIGGERED = "OVERDRAFT_TRIGGERED";

    // transaction-events
    public static final String TRANSACTION_CREATED = "TRANSACTION_CREATED";
    public static final String TRANSFER_COMPLETED = "TRANSFER_COMPLETED";

    // application-events
    public static final String APPLICATION_SUBMITTED = "APPLICATION_SUBMITTED";
    public static final String APPLICATION_APPROVED = "APPLICATION_APPROVED";
    public static final String APPLICATION_REJECTED = "APPLICATION_REJECTED";
}
