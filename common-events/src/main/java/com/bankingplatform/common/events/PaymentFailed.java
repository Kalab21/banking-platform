package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A payment could not be completed.
 *
 * <p>Consumers: {@code notification-service}, {@code statistics-service} and
 * {@code fraud-detection-service}, which counts repeated failures on an
 * account as a fraud signal.
 *
 * <p>The event carried only the payment id, its reference and a reason. The
 * notification needs {@code userId} and the fraud rule needs
 * {@code payerAccountId}; neither was ever sent, so both returned
 * immediately. Repeated failed payments — the signal the rule exists to catch
 * — had never been counted.
 *
 * <p>There is deliberately no failure reason on the event. No consumer reads
 * one, and the obvious value to put there — the caught exception's message —
 * can contain a downstream response body or caller-supplied text. Copying
 * that onto a topic to satisfy a field nothing consumes is exactly the kind
 * of incidental data spread this contract is meant to stop. The reason is
 * still persisted on the payment row, where it is needed and access-controlled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentFailed(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long paymentId,
        String paymentRef,
        Long payerAccountId,
        Long userId) implements DomainEvent {

    public static final int VERSION = 1;

    public static PaymentFailed of(Long paymentId, String paymentRef, Long payerAccountId, Long userId) {
        return new PaymentFailed(EventMeta.newId(), EventTypes.PAYMENT_FAILED, VERSION,
                EventMeta.now(), paymentId, paymentRef, payerAccountId, userId);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(payerAccountId);
    }
}
