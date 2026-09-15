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
public class LoanEventConsumer {

    private final CreditScoreService creditScoreService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "loan-events", groupId = "user-service")
    public void consume(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, new TypeReference<>() {});
            String eventType = (String) event.get("eventType");
            Object userIdObj = event.get("userId");
            if (userIdObj == null) return;

            Long userId = Long.valueOf(userIdObj.toString());

            switch (eventType) {
                case "LOAN_PAID_OFF" ->
                        creditScoreService.updateScore(userId, +15, "Loan paid off in full");
                case "LOAN_REPAYMENT_MADE" ->
                        creditScoreService.updateScore(userId, +5, "On-time loan repayment");
                case "LOAN_PAYMENT_MISSED" ->
                        creditScoreService.updateScore(userId, -20, "Missed loan payment");
                default -> log.debug("Unhandled loan event: {}", eventType);
            }
        } catch (Exception e) {
            log.warn("Failed to process loan event: {}", e.getMessage());
        }
    }
}
