package com.bankingplatform.integration.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalTransferEventProducer {

    private static final String TOPIC = "integration-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishTransferInitiated(Long id, String ref, String transferType,
                                         Long fromAccountId, BigDecimal amount,
                                         String currency, LocalDate estimatedArrival) {
        send(ref, Map.of(
                "eventType", "EXTERNAL_TRANSFER_INITIATED",
                "transferId", id,
                "transferRef", ref,
                "transferType", transferType,
                "fromAccountId", fromAccountId,
                "amount", amount,
                "currency", currency,
                "estimatedArrival", estimatedArrival.toString(),
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published EXTERNAL_TRANSFER_INITIATED: ref={}, type={}, amount={}", ref, transferType, amount);
    }

    private void send(String key, Map<String, Object> event) {
        try {
            kafkaTemplate.send(TOPIC, key, event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — event not published for key {}: {}", key, e.getMessage());
        }
    }
}
