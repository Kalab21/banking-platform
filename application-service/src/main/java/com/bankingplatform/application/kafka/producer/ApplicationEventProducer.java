package com.bankingplatform.application.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationEventProducer {

    private static final String TOPIC = "application-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishApplicationSubmitted(Long applicationId, Long userId, String applicationType) {
        send(applicationId, Map.of(
                "eventType", "APPLICATION_SUBMITTED",
                "applicationId", applicationId,
                "userId", userId,
                "applicationType", applicationType,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published APPLICATION_SUBMITTED for application {}", applicationId);
    }

    public void publishApplicationApproved(Long applicationId, Long userId, String applicationType,
                                             Long productId, Integer creditScore, java.math.BigDecimal requestedAmount) {
        java.util.HashMap<String, Object> payload = new java.util.HashMap<>();
        payload.put("eventType", "APPLICATION_APPROVED");
        payload.put("applicationId", applicationId);
        payload.put("userId", userId);
        payload.put("applicationType", applicationType);
        payload.put("productType", applicationType);
        payload.put("productId", productId);
        payload.put("creditScore", creditScore);
        payload.put("requestedAmount", requestedAmount);
        payload.put("timestamp", LocalDateTime.now().toString());
        send(applicationId, payload);
        log.info("Published APPLICATION_APPROVED for application {}", applicationId);
    }

    public void publishApplicationRejected(Long applicationId, Long userId, String applicationType, String reason) {
        send(applicationId, Map.of(
                "eventType", "APPLICATION_REJECTED",
                "applicationId", applicationId,
                "userId", userId,
                "applicationType", applicationType,
                "reason", reason,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published APPLICATION_REJECTED for application {}", applicationId);
    }

    private void send(Long key, Map<String, Object> event) {
        try {
            kafkaTemplate.send(TOPIC, key.toString(), event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — event not published for key {}: {}", key, e.getMessage());
        }
    }
}
