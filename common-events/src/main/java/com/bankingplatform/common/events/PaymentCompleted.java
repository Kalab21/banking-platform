package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment was made from an account.
 *
 * <p>Consumers: {@code notification-service} (tells the payer) and
 * {@code statistics-service} (payment totals).
 *
 * <p>The notification had never fired, because it reads {@code userId} and
 * the event carried only account ids. {@code payment-service} does not store
 * the payer's user id — a payment belongs to an account — so it resolves the
 * owner from {@code account-service}, which is the same service and the same
 * question its authorization already asks.
 *
 * <p>{@code payeeAccountId} is a real {@code Long} or absent. It used to be
 * set to an empty <em>string</em> when there was no payee account, so the
 * field changed type depending on the payment, and a consumer parsing it had
 * to cope with both.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentCompleted(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long paymentId,
        String paymentRef,
        Long payerAccountId,
        Long userId,
        Long payeeAccountId,
        BigDecimal amount,
        String paymentType) implements DomainEvent {

    public static final int VERSION = 1;

    public static PaymentCompleted of(Long paymentId, String paymentRef, Long payerAccountId,
                                      Long userId, Long payeeAccountId, BigDecimal amount,
                                      String paymentType) {
        return new PaymentCompleted(EventMeta.newId(), EventTypes.PAYMENT_COMPLETED, VERSION,
                EventMeta.now(), paymentId, paymentRef, payerAccountId, userId, payeeAccountId,
                amount, paymentType);
    }

    /** Keyed by the paying account, so one account's payments stay ordered. */
    @Override
    public String partitionKey() {
        return String.valueOf(payerAccountId);
    }
}
