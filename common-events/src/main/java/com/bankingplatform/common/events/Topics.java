package com.bankingplatform.common.events;

/**
 * The Kafka topic names.
 *
 * <p>Shared for the same reason as {@link EventTypes}: a producer writing to
 * one topic while a consumer listens on another is invisible until someone
 * notices a feature has never worked. {@code account-service} publishes
 * {@code OVERDRAFT_TRIGGERED} to {@code account-events} while
 * {@code notification-service} listened for it on {@code transaction-events},
 * so the overdraft notification had never fired.
 */
public final class Topics {

    private Topics() {
    }

    public static final String ACCOUNT_EVENTS = "account-events";
    public static final String TRANSACTION_EVENTS = "transaction-events";
    public static final String APPLICATION_EVENTS = "application-events";
    public static final String CREDIT_CARD_EVENTS = "credit-card-events";
    public static final String LOAN_EVENTS = "loan-events";
    public static final String PAYMENT_EVENTS = "payment-events";
    public static final String USER_EVENTS = "user-events";

    /** Published, with no consumer today. See {@code docs/EVENTS.md}. */
    public static final String INTEGRATION_EVENTS = "integration-events";

    /** Published, with no consumer today. See {@code docs/EVENTS.md}. */
    public static final String FRAUD_ALERT_EVENTS = "fraud-alert-events";
}
