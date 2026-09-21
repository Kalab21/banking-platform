package com.bankingplatform.transaction.kafka.producer;

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
@DisplayName("Transaction event producer")
class TransactionEventProducerTest {

    @Mock
    private OutboxPublisher outbox;

    @InjectMocks
    private TransactionEventProducer producer;

    @Captor
    private ArgumentCaptor<DomainEvent> event;

    private DomainEvent published() {
        verify(outbox).publish(eq(Topics.TRANSACTION_EVENTS), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("a transaction is keyed by account, not by its own reference")
    void transactionCreated() {
        producer.publishTransactionCreated(1L, 9L, 42L, "DEPOSIT", new BigDecimal("50.00"), new BigDecimal("150.00"), "ref-1");

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.TRANSACTION_CREATED);
        assertThat(sent.partitionKey()).isEqualTo("9");
    }

    @Test
    @DisplayName("a transfer is keyed by the account the money left")
    void transferCompleted() {
        producer.publishTransferCompleted("debit-1", "credit-1", 9L, 42L, 10L, new BigDecimal("25.00"));

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.TRANSFER_COMPLETED);
        assertThat(sent.partitionKey()).isEqualTo("9");
    }

}
