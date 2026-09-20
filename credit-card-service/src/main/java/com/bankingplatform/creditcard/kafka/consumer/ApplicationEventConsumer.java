package com.bankingplatform.creditcard.kafka.consumer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.creditcard.dto.request.CreateCreditCardRequest;
import com.bankingplatform.creditcard.model.CardType;
import com.bankingplatform.creditcard.service.CreditCardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Issues the card behind an approved application.
 *
 * <p>Reads the shared {@link ApplicationApproved} type rather than a map.
 *
 * <p>This handler creates a financial product, so a redelivery of the same
 * event would issue a second card. {@link DomainEvent#eventId()} is the
 * identity that makes de-duplication possible; using it is a separate change
 * and this consumer is not yet idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationEventConsumer {

    private static final String CREDIT_CARD = "CREDIT_CARD";

    private final CreditCardService creditCardService;

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "credit-card-service")
    public void onApplicationEvent(DomainEvent event) {
        if (!(event instanceof ApplicationApproved approved)
                || !CREDIT_CARD.equals(approved.productType())) {
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
