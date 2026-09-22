package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * Every event this platform publishes on Kafka.
 *
 * <p>Events used to be {@code Map<String, Object>} literals built at the call
 * site, with the field names written out again by hand in each consumer. That
 * is a contract nothing checks. A producer could rename a field, drop one, or
 * publish an event type spelled differently from the one a consumer matched
 * on, and the build stayed green — the consumer simply read {@code null},
 * took its "nothing to do" branch and committed the offset. Several such
 * breaks were live in this repository and are listed in
 * {@code docs/EVENTS.md}.
 *
 * <p>The fix is that the event is a type. A producer cannot omit a required
 * field because the constructor demands it, and a consumer cannot invent one
 * because the accessor would not compile.
 *
 * <h2>Envelope</h2>
 *
 * Four fields are on every event:
 *
 * <ul>
 *   <li>{@link #eventId()} — a UUID identifying <em>this publication</em>.
 *       Kafka is at-least-once, so the same event can arrive twice; a
 *       consumer that must not act twice keys its de-duplication on this.</li>
 *   <li>{@link #eventType()} — the discriminator. It is a real field in the
 *       JSON as well as the Jackson type id, so the wire format stays
 *       readable and a human can tell what a record is without a schema.</li>
 *   <li>{@link #eventVersion()} — incremented when a field's meaning changes
 *       in a way a consumer must notice. Adding an optional field does not
 *       need a new version; removing one or changing units does.</li>
 *   <li>{@link #occurredAt()} — when the business fact happened, not when it
 *       was published. A retry from an outbox must not restate the time.</li>
 * </ul>
 *
 * <h2>Compatibility</h2>
 *
 * <p>The JSON stays flat: envelope fields and payload fields sit side by side,
 * exactly as the old maps did. That keeps records inspectable in Kafka UI and
 * means a consumer reading one field sees the same shape it always did.
 *
 * <p>Unknown event types deserialize to {@link UnknownEvent} rather than
 * throwing. A service must be able to publish a new event type without every
 * existing consumer of that topic failing on the first record — otherwise a
 * routine addition becomes a coordinated deployment, and a poison record for
 * every consumer group that has not been updated yet.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "eventType",
        visible = true,
        defaultImpl = UnknownEvent.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AccountCreated.class, name = EventTypes.ACCOUNT_CREATED),
        @JsonSubTypes.Type(value = BalanceUpdated.class, name = EventTypes.BALANCE_UPDATED),
        @JsonSubTypes.Type(value = OverdraftTriggered.class, name = EventTypes.OVERDRAFT_TRIGGERED),
        @JsonSubTypes.Type(value = TransactionCreated.class, name = EventTypes.TRANSACTION_CREATED),
        @JsonSubTypes.Type(value = TransferCompleted.class, name = EventTypes.TRANSFER_COMPLETED),
        @JsonSubTypes.Type(value = ApplicationSubmitted.class, name = EventTypes.APPLICATION_SUBMITTED),
        @JsonSubTypes.Type(value = ApplicationApproved.class, name = EventTypes.APPLICATION_APPROVED),
        @JsonSubTypes.Type(value = ApplicationRejected.class, name = EventTypes.APPLICATION_REJECTED),
        @JsonSubTypes.Type(value = CreditCardCreated.class, name = EventTypes.CREDIT_CARD_CREATED),
        @JsonSubTypes.Type(value = LoanCreated.class, name = EventTypes.LOAN_CREATED),
        @JsonSubTypes.Type(value = CreditCardTransactionCompleted.class,
                name = EventTypes.CREDIT_CARD_TRANSACTION_COMPLETED),
        @JsonSubTypes.Type(value = CreditCardStatementGenerated.class,
                name = EventTypes.CREDIT_CARD_STATEMENT_GENERATED),
        @JsonSubTypes.Type(value = LoanDisbursed.class, name = EventTypes.LOAN_DISBURSED),
        @JsonSubTypes.Type(value = LoanRepaymentMade.class, name = EventTypes.LOAN_REPAYMENT_MADE),
        @JsonSubTypes.Type(value = LoanPaidOff.class, name = EventTypes.LOAN_PAID_OFF),
        @JsonSubTypes.Type(value = PaymentCompleted.class, name = EventTypes.PAYMENT_COMPLETED),
        @JsonSubTypes.Type(value = PaymentFailed.class, name = EventTypes.PAYMENT_FAILED),
        @JsonSubTypes.Type(value = ExternalTransferInitiated.class,
                name = EventTypes.EXTERNAL_TRANSFER_INITIATED),
        @JsonSubTypes.Type(value = FraudAlertCreated.class, name = EventTypes.FRAUD_ALERT_CREATED),
        @JsonSubTypes.Type(value = UserLifecycleEvents.KycApproved.class, name = EventTypes.KYC_APPROVED),
        @JsonSubTypes.Type(value = UserLifecycleEvents.KycRejected.class, name = EventTypes.KYC_REJECTED),
        @JsonSubTypes.Type(value = UserLifecycleEvents.TwoFactorEnabled.class, name = EventTypes.TWO_FA_ENABLED),
        @JsonSubTypes.Type(value = UserLifecycleEvents.TwoFactorDisabled.class, name = EventTypes.TWO_FA_DISABLED),
})
public interface DomainEvent {

    /** Identifies this publication, for consumer de-duplication. */
    String eventId();

    /** The discriminator, one of the constants in {@link EventTypes}. */
    String eventType();

    /** Incremented when a change would break a consumer that ignored it. */
    int eventVersion();

    /** When the business fact happened. */
    Instant occurredAt();

    /**
     * The Kafka message key, chosen as the aggregate whose ordering matters.
     *
     * <p>Kafka orders records within a partition, not within a topic, and the
     * key decides the partition. Two events about the same account must land
     * on the same partition or a consumer can see them out of order, so the
     * key is the account, the application or the card — never a per-event
     * value like a transaction reference, which would scatter one aggregate's
     * history across every partition.
     *
     * <p>Not serialised: it is how the record is addressed, not part of it.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    String partitionKey();
}
