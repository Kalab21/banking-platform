package com.bankingplatform.user.kafka.consumer;

import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.LoanPaidOff;
import com.bankingplatform.common.events.LoanRepaymentMade;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.user.service.CreditScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Moves a credit score as a loan is repaid.
 *
 * <p>Neither reward had ever been applied: the repayment event carried no
 * {@code userId}, and this returned on the null before it reached the switch.
 *
 * <p>The {@code LOAN_PAYMENT_MISSED} penalty is gone. Nothing produces that
 * event — there is no job anywhere that notices an instalment has gone
 * unpaid — so a -20 branch that could never run was a claim this platform
 * detects delinquency when it does not. Recorded in {@code docs/EVENTS.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoanEventConsumer {

    private final CreditScoreService creditScoreService;

    @KafkaListener(topics = Topics.LOAN_EVENTS, groupId = "user-service")
    public void consume(DomainEvent event) {
        // instanceof chains rather than a switch over patterns: this builds on
        // Java 17, where switch patterns are still a preview feature.
        if (event instanceof LoanPaidOff e && e.userId() != null) {
            creditScoreService.updateScore(e.userId(), +15, "Loan paid off in full");
        } else if (event instanceof LoanRepaymentMade e && e.userId() != null) {
            creditScoreService.updateScore(e.userId(), +5, "On-time loan repayment");
        }
    }
}
