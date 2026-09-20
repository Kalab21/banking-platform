package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A loan reached a zero balance.
 *
 * <p>Consumers: {@code notification-service}, {@code statistics-service} and
 * {@code user-service}, which raises the credit score.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoanPaidOff(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long loanId,
        Long userId) implements DomainEvent {

    public static final int VERSION = 1;

    public static LoanPaidOff of(Long loanId, Long userId) {
        return new LoanPaidOff(EventMeta.newId(), EventTypes.LOAN_PAID_OFF, VERSION,
                EventMeta.now(), loanId, userId);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(loanId);
    }
}
