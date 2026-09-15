package com.bankingplatform.creditcard.scheduler;

import com.bankingplatform.creditcard.service.CreditCardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class InterestChargeJob {

    private final CreditCardService creditCardService;

    @Scheduled(cron = "0 0 0 * * *") // midnight daily
    public void chargeInterest() {
        log.info("Running daily interest charge job");
        creditCardService.chargeInterest();
    }
}
