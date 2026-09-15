package com.bankingplatform.creditcard.kafka.consumer;

import com.bankingplatform.creditcard.dto.request.CreateCreditCardRequest;
import com.bankingplatform.creditcard.model.CardType;
import com.bankingplatform.creditcard.service.CreditCardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationEventConsumer {

    private final CreditCardService creditCardService;

    @KafkaListener(topics = "application-events", groupId = "credit-card-service")
    public void onApplicationEvent(Map<String, Object> event) {
        try {
            String eventType = (String) event.get("eventType");
            String productType = (String) event.get("productType");

            if (!"APPLICATION_APPROVED".equals(eventType) || !"CREDIT_CARD".equals(productType)) {
                return;
            }

            Long userId = toLong(event.get("userId"));
            Long applicationId = toLong(event.get("applicationId"));
            Integer creditScore = toInt(event.get("creditScore"));

            CreateCreditCardRequest request = new CreateCreditCardRequest();
            request.setUserId(userId);
            request.setApplicationId(applicationId);
            request.setCardType(resolveCardType(creditScore));
            request.setCreditLimit(resolveCreditLimit(creditScore));
            request.setApr(resolveApr(creditScore));

            creditCardService.createCard(request);
            log.info("Created credit card for userId={}, applicationId={}", userId, applicationId);
        } catch (Exception e) {
            log.error("Error processing application event: {}", e.getMessage(), e);
        }
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

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }

    private Integer toInt(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }
}
