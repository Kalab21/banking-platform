package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A fraud rule fired and an alert was opened.
 *
 * <p><b>No consumer subscribes to {@code fraud-alert-events} today.</b> Kept
 * and documented as unconsumed rather than removed: an alert is a fact worth
 * emitting, and a case-management or staff-notification consumer is the
 * obvious future subscriber. No consumer is invented here to balance the
 * matrix.
 *
 * <p>It carries the rule outcome and the subject's ids, never the transaction
 * detail that triggered it.
 *
 * <p>This event also fixes a partitioning bug: the alert was published with
 * no key at all, so alerts round-robined across partitions and two alerts on
 * one account could be consumed out of order by any future consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudAlertCreated(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long alertId,
        Long accountId,
        Long userId,
        String alertType,
        int riskScore) implements DomainEvent {

    public static final int VERSION = 1;

    public static FraudAlertCreated of(Long alertId, Long accountId, Long userId,
                                       String alertType, int riskScore) {
        return new FraudAlertCreated(EventMeta.newId(), EventTypes.FRAUD_ALERT_CREATED, VERSION,
                EventMeta.now(), alertId, accountId, userId, alertType, riskScore);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(accountId);
    }
}
