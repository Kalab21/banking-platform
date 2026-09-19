package com.bankingplatform.integration.kafka.producer;

import com.bankingplatform.common.events.ExternalTransferInitiated;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Publishes an accepted outward transfer.
 *
 * <p>Nothing consumes {@code integration-events} today. That is recorded in
 * {@code docs/EVENTS.md} rather than papered over by inventing a consumer:
 * the event is a genuine record of an outward instruction, and a future
 * notification or settlement-tracking consumer is the obvious subscriber.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalTransferEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishTransferInitiated(Long id, String ref, String transferType,
                                         Long fromAccountId, BigDecimal amount,
                                         String currency, LocalDate estimatedArrival) {
        ExternalTransferInitiated event = ExternalTransferInitiated.of(id, ref, transferType,
                fromAccountId, amount, currency,
                estimatedArrival != null ? estimatedArrival.toString() : null);
        try {
            kafkaTemplate.send(Topics.INTEGRATION_EVENTS, event.partitionKey(), event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — {} not published for key {}: {}",
                    event.eventType(), event.partitionKey(), e.getMessage());
        }
        log.info("Published EXTERNAL_TRANSFER_INITIATED: ref={}, type={}, amount={}", ref, transferType, amount);
    }
}
