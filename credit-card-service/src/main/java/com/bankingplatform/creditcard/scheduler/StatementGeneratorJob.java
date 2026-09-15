package com.bankingplatform.creditcard.scheduler;

import com.bankingplatform.creditcard.model.CardStatus;
import com.bankingplatform.creditcard.repository.CreditCardRepository;
import com.bankingplatform.creditcard.service.CreditCardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatementGeneratorJob {

    private final CreditCardService creditCardService;
    private final CreditCardRepository cardRepository;

    @Scheduled(cron = "0 0 1 1 * *") // 1am on 1st of each month
    public void generateMonthlyStatements() {
        log.info("Running monthly statement generation job");
        int day = LocalDate.now().getDayOfMonth();
        cardRepository.findByStatus(CardStatus.ACTIVE).stream()
                .filter(c -> c.getBillingCycleDay() == day)
                .forEach(card -> {
                    try {
                        creditCardService.generateStatement(card.getId());
                        log.info("Statement generated for cardId={}", card.getId());
                    } catch (Exception e) {
                        log.error("Statement generation failed for cardId={}: {}", card.getId(), e.getMessage());
                    }
                });
    }
}
