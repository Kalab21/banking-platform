package com.bankingplatform.creditcard.kafka.consumer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.creditcard.dto.request.CreateCreditCardRequest;
import com.bankingplatform.creditcard.model.CardType;
import com.bankingplatform.creditcard.service.CreditCardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Issues the card behind an approved application.
 *
 * <p>Reads the shared {@link ApplicationApproved} type rather than a map.
 *
 * <p>This handler creates a financial product, so a redelivery of the same
 * event would issue a second card. It claims the event id in the same
 * transaction that issues the card: both happen or neither does.
 *
 * <p>A partial unique index on {@code credit_cards.application_id} backs that
 * up, so one approved application cannot produce two cards even if a
 * duplicate arrives by a route that misses this guard.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationEventConsumer {

    private static final String CREDIT_CARD = "CREDIT_CARD";

    /** Identifies this handler in the processed-event table. */
    private static final String CONSUMER = "credit-card-service:application-approved";

    private final CreditCardService creditCardService;
    private final ProcessedEventGuard processedEvents;

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "credit-card-service")
    @Transactional
    public void onApplicationEvent(DomainEvent event) {
        if (!(event instanceof ApplicationApproved approved)
                || !CREDIT_CARD.equals(approved.productType())) {
            return;
        }

        if (approved.userId() == null) {
            log.warn("Ignoring an approved application with no applicant: applicationId={}",
                    approved.applicationId());
            return;
        }

        // Claimed in the same transaction as the card it issues, so a
        // redelivery cannot produce a second card.
        if (!processedEvents.claim(CONSUMER, approved.eventId())) {
            log.info("Already issued a card for event {} (applicationId={}); ignoring redelivery",
                    approved.eventId(), approved.applicationId());
            return;
        }

        Integer creditScore = approved.creditScore();

        CreateCreditCardRequest request = new CreateCreditCardRequest();
        request.setUserId(approved.userId());
        request.setApplicationId(approved.applicationId());
        request.setCardType(resolveCardType(creditScore));
        request.setCreditLimit(resolveCreditLimit(creditScore));
        request.setApr(resolveApr(creditScore));

        creditCardService.createCard(request);
        log.info("Created credit card for userId={}, applicationId={}",
                approved.userId(), approved.applicationId());
    }

    private CardType resolveCardType(Integer creditScore) {
        if (creditScore == null) return CardType.STANDARD;
        if (creditScore >= 800) return CardType.PLATINUM;
        if (creditScore >= 720) return CardType.GOLD;
        return CardType.STANDARD;
    }

    private BigDecimal resolveCreditLimit(Integer creditScore) {
        if (creditScore == null) return new BigDecimal("1000");
        if (creditScore >= 800) return new BigDecimal("10000");
        if (creditScore >= 720) return new BigDecimal("5000");
        if (creditScore >= 680) return new BigDecimal("3000");
        return new BigDecimal("1000");
    }

    private BigDecimal resolveApr(Integer creditScore) {
        if (creditScore == null) return new BigDecimal("24.99");
        if (creditScore >= 800) return new BigDecimal("14.99");
        if (creditScore >= 720) return new BigDecimal("18.99");
        if (creditScore >= 680) return new BigDecimal("21.99");
        return new BigDecimal("24.99");
    }
}
