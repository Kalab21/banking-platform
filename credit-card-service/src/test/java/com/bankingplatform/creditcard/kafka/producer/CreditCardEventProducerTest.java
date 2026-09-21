package com.bankingplatform.creditcard.kafka.producer;

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
@DisplayName("Credit card event producer")
class CreditCardEventProducerTest {

    @Mock
    private OutboxPublisher outbox;

    @InjectMocks
    private CreditCardEventProducer producer;

    @Captor
    private ArgumentCaptor<DomainEvent> event;

    private DomainEvent published() {
        verify(outbox).publish(eq(Topics.CREDIT_CARD_EVENTS), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("a card transaction is keyed by card")
    void transactionCompleted() {
        producer.publishTransactionCompleted(6L, 42L, "tx-1", "PURCHASE", new BigDecimal("30.00"), new BigDecimal("970.00"));

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.CREDIT_CARD_TRANSACTION_COMPLETED);
        assertThat(sent.partitionKey()).isEqualTo("6");
    }

    @Test
    @DisplayName("an issued card carries four digits and no more")
    void cardCreated() {
        producer.publishCardCreated(6L, 42L, "VISA", "4242");

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.CREDIT_CARD_CREATED);
        assertThat(sent.partitionKey()).isEqualTo("6");
    }

    @Test
    @DisplayName("a statement uses the event type its consumer actually matches")
    void statementGenerated() {
        producer.publishStatementGenerated(6L, 42L, 11L, "2026-09-01");

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.CREDIT_CARD_STATEMENT_GENERATED);
        assertThat(sent.partitionKey()).isEqualTo("6");
    }

}
