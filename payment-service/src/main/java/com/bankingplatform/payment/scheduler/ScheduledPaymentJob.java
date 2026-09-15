package com.bankingplatform.payment.scheduler;

import com.bankingplatform.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledPaymentJob {

    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 60000)
    public void processScheduledPayments() {
        log.debug("Running scheduled payment processor");
        paymentService.processScheduledPayments();
    }
}
