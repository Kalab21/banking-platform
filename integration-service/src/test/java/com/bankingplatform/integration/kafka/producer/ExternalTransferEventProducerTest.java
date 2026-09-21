package com.bankingplatform.integration.kafka.producer;

import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.EventTypes;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * What this producer puts in the outbox: which topic, which event, which key.
 *
 * <p>Thin, and worth having. The topic is a constant chosen at the call site,
 * and the key decides the partition -- so a wrong one either routes events
 * where no consumer is listening, or scatters one aggregate across partitions
 * and silently gives up the ordering Kafka offers. Neither fails anywhere.
 * Nothing checked either until now, through two rounds of edits to every
 * producer on the platform.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("External transfer event producer")
class ExternalTransferEventProducerTest {

    @Mock
    private OutboxPublisher outbox;

    @InjectMocks
    private ExternalTransferEventProducer producer;

    @Captor
    private ArgumentCaptor<DomainEvent> event;

    private DomainEvent published() {
        verify(outbox).publish(eq(Topics.INTEGRATION_EVENTS), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("an outward transfer is keyed by the account it leaves")
    void transferInitiated() {
        producer.publishTransferInitiated(8L, "wire-1", "WIRE", 9L, new BigDecimal("500.00"), "USD", LocalDate.of(2026, 9, 25));

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.EXTERNAL_TRANSFER_INITIATED);
        assertThat(sent.partitionKey()).isEqualTo("9");
    }

}
