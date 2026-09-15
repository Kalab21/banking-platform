package com.bankingplatform.user.kafka.consumer;

import com.bankingplatform.user.service.CreditScoreService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class CreditCardEventConsumer {

    private final CreditScoreService creditScoreService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "credit-card-events", groupId = "user-service")
    public void consume(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, new TypeReference<>() {});
            String eventType = (String) event.get("eventType");
            Object userIdObj = event.get("userId");
            if (userIdObj == null) return;

            Long userId = Long.valueOf(userIdObj.toString());

            if ("CREDIT_CARD_TRANSACTION_COMPLETED".equals(eventType)) {
                String txType = (String) event.getOrDefault("transactionType", "");
                if ("PAYMENT".equals(txType)) {
                    creditScoreService.updateScore(userId, +5, "Credit card payment made");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process credit card event: {}", e.getMessage());
        }
    }
}
