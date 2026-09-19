package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A repayment was applied to a loan.
 *
 * <p>Consumers: {@code statistics-service} (repayment totals) and
 * {@code user-service}, which raises the credit score for an on-time
 * repayment.
 *
 * <p>The credit-score effect had never happened: the event carried no
 * {@code userId} and the consumer returns as soon as it reads null. It is
 * required here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoanRepaymentMade(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long loanId,
        Long userId,
        String paymentRef,
        BigDecimal amount,
        BigDecimal remainingBalance) implements DomainEvent {

    public static final int VERSION = 1;

    public static LoanRepaymentMade of(Long loanId, Long userId, String paymentRef,
                                       BigDecimal amount, BigDecimal remainingBalance) {
        return new LoanRepaymentMade(EventMeta.newId(), EventTypes.LOAN_REPAYMENT_MADE, VERSION,
                EventMeta.now(), loanId, userId, paymentRef, amount, remainingBalance);
    }

    /**
     * Keyed by loan, not by payment reference.
     *
     * <p>The reference was the key, which is unique per repayment, so a loan's
     * repayments were spread across partitions and could be consumed out of
     * order — and {@code remainingBalance} is only meaningful in order.
     */
    @Override
    public String partitionKey() {
        return String.valueOf(loanId);
    }
}
