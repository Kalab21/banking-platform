package com.bankingplatform.loan.kafka.producer;

import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.LoanCreated;
import com.bankingplatform.common.events.LoanDisbursed;
import com.bankingplatform.common.events.LoanPaidOff;
import com.bankingplatform.common.events.LoanRepaymentMade;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Publishes what happened to a loan.
 *
 * <p>The repayment event now names the borrower. It did not, and
 * {@code user-service} raises a credit score on an on-time repayment by
 * reading {@code userId} — so that reward had never been applied to anyone.
 *
 * <p>Repayments are keyed by loan rather than by payment reference, so a
 * loan's repayments stay in order on one partition. {@code remainingBalance}
 * only means anything in order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoanEventProducer {

    private final OutboxPublisher outbox;

    /**
     * The confirmation application-service waits for. Until this arrives it
     * cannot honestly say the loan exists.
     */
    public void publishLoanCreated(Long loanId, Long userId, Long applicationId, String loanType,
                                   BigDecimal principal, BigDecimal interestRate, Integer termMonths) {
        send(LoanCreated.of(loanId, userId, applicationId, loanType, principal, interestRate, termMonths));
        log.info("Published LOAN_CREATED: loanId={}, applicationId={}", loanId, applicationId);
    }

    public void publishLoanDisbursed(Long loanId, Long userId, BigDecimal principal, Long accountId) {
        send(LoanDisbursed.of(loanId, userId, principal, accountId));
        log.info("Published LOAN_DISBURSED: loanId={}, amount={}", loanId, principal);
    }

    public void publishRepaymentMade(Long loanId, Long userId, String ref,
                                     BigDecimal amount, BigDecimal remainingBalance) {
        send(LoanRepaymentMade.of(loanId, userId, ref, amount, remainingBalance));
        log.info("Published LOAN_REPAYMENT_MADE: loanId={}, amount={}", loanId, amount);
    }

    public void publishLoanPaidOff(Long loanId, Long userId) {
        send(LoanPaidOff.of(loanId, userId));
        log.info("Published LOAN_PAID_OFF: loanId={}", loanId);
    }

    /**
     * Records the event in the outbox, in the caller's transaction.
     *
     * <p>A repayment that is recorded and never announced leaves the customer
     * uncredited: {@code user-service} raises a credit score on this event,
     * and it is the only route by which an on-time repayment ever does. A
     * disbursement that is not announced is money lent and nothing said.
     */
    private void send(DomainEvent event) {
        outbox.publish(Topics.LOAN_EVENTS, event);
    }
}
