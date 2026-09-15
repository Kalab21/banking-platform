package com.bankingplatform.loan.scheduler;

import com.bankingplatform.loan.service.LoanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MissedPaymentJob {

    private final LoanService loanService;

    @Scheduled(cron = "0 0 8 * * *") // 8am daily
    public void checkMissedPayments() {
        log.info("Running missed payment detection job");
        loanService.markMissedPayments();
    }
}
