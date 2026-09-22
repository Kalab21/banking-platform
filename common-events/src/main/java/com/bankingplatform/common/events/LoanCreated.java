package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A loan now exists, for this application, on these terms.
 *
 * <p>This is the confirmation that closes the loop. {@code application-service}
 * asks for a product by publishing an approval and then has to wait: until this
 * arrives it does not know whether the loan was created, and saying
 * {@code PROVISIONED} on the strength of having sent a message would claim a
 * product exists that may not.
 *
 * <p>It carries the terms as written so the application can be checked against
 * the offer that was accepted, rather than trusted to match it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoanCreated(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long loanId,
        Long userId,
        Long applicationId,
        String loanType,
        BigDecimal principal,
        BigDecimal interestRate,
        Integer termMonths) implements DomainEvent {

    public static final int VERSION = 1;

    public static LoanCreated of(Long loanId, Long userId, Long applicationId, String loanType,
                                 BigDecimal principal, BigDecimal interestRate, Integer termMonths) {
        return new LoanCreated(EventMeta.newId(), EventTypes.LOAN_CREATED, VERSION,
                EventMeta.now(), loanId, userId, applicationId, loanType,
                principal, interestRate, termMonths);
    }

    /** Keyed by loan, like every other loan event. */
    @Override
    public String partitionKey() {
        return String.valueOf(loanId);
    }
}
