package com.bankingplatform.payment.kafka.producer;

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
@DisplayName("Payment event producer")
class PaymentEventProducerTest {

    @Mock
    private OutboxPublisher outbox;

    @InjectMocks
    private PaymentEventProducer producer;

    @Captor
    private ArgumentCaptor<DomainEvent> event;

    private DomainEvent published() {
        verify(outbox).publish(eq(Topics.PAYMENT_EVENTS), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("a completed payment is keyed by the paying account")
    void completed() {
        producer.publishPaymentCompleted(3L, "pay-1", 9L, 42L, 10L, new BigDecimal("75.00"), "BILL");

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.PAYMENT_COMPLETED);
        assertThat(sent.partitionKey()).isEqualTo("9");
    }

    @Test
    @DisplayName("a failed payment reaches the topic the fraud rule counts from")
    void failed() {
        producer.publishPaymentFailed(3L, "pay-1", 9L, 42L);

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.PAYMENT_FAILED);
        assertThat(sent.partitionKey()).isEqualTo("9");
    }

}
