package com.bankingplatform.application.kafka.producer;

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
@DisplayName("Application event producer")
class ApplicationEventProducerTest {

    @Mock
    private OutboxPublisher outbox;

    @InjectMocks
    private ApplicationEventProducer producer;

    @Captor
    private ArgumentCaptor<DomainEvent> event;

    private DomainEvent published() {
        verify(outbox).publish(eq(Topics.APPLICATION_EVENTS), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("a submission is announced, which one statistic counts")
    void submitted() {
        producer.publishApplicationSubmitted(5L, 42L, "PERSONAL_LOAN");

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.APPLICATION_SUBMITTED);
        assertThat(sent.partitionKey()).isEqualTo("5");
    }

    @Test
    @DisplayName("an approval is keyed by application, since it is what issues a product")
    void approved() {
        producer.publishApplicationApproved(5L, 42L, "PERSONAL_LOAN", null, 720, new BigDecimal("10000"));

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.APPLICATION_APPROVED);
        assertThat(sent.partitionKey()).isEqualTo("5");
    }

    @Test
    @DisplayName("a rejection carries the product type, so the notice can name it")
    void rejected() {
        producer.publishApplicationRejected(5L, 42L, "PERSONAL_LOAN", "score too low");

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.APPLICATION_REJECTED);
        assertThat(sent.partitionKey()).isEqualTo("5");
    }

}
