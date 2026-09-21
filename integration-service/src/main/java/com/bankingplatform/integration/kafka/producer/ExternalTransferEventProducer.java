package com.bankingplatform.integration.kafka.producer;

import com.bankingplatform.common.events.ExternalTransferInitiated;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
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

    private final OutboxPublisher outbox;

    public void publishTransferInitiated(Long id, String ref, String transferType,
                                         Long fromAccountId, BigDecimal amount,
                                         String currency, LocalDate estimatedArrival) {
        ExternalTransferInitiated event = ExternalTransferInitiated.of(id, ref, transferType,
                fromAccountId, amount, currency,
                estimatedArrival != null ? estimatedArrival.toString() : null);
        // Recorded in the caller's transaction rather than sent here. Nothing
        // consumes this topic today, which is not licence to publish
        // unreliably: an outbound instruction is exactly the record a future
        // reconciliation or audit consumer cannot be given retrospectively if
        // the event was never written down.
        outbox.publish(Topics.INTEGRATION_EVENTS, event);
        log.info("Recorded EXTERNAL_TRANSFER_INITIATED: ref={}, type={}, amount={}", ref, transferType, amount);
    }
}
