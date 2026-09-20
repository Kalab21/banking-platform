package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A loan was paid out to the borrower's account.
 *
 * <p>Consumers: {@code notification-service} and {@code statistics-service}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoanDisbursed(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long loanId,
        Long userId,
        BigDecimal principal,
        Long disbursementAccountId) implements DomainEvent {

    public static final int VERSION = 1;

    public static LoanDisbursed of(Long loanId, Long userId, BigDecimal principal, Long accountId) {
        return new LoanDisbursed(EventMeta.newId(), EventTypes.LOAN_DISBURSED, VERSION,
                EventMeta.now(), loanId, userId, principal, accountId);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(loanId);
    }
}
