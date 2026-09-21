package com.bankingplatform.account.kafka.producer;

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
@DisplayName("Account event producer")
class AccountEventProducerTest {

    @Mock
    private OutboxPublisher outbox;

    @InjectMocks
    private AccountEventProducer producer;

    @Captor
    private ArgumentCaptor<DomainEvent> event;

    private DomainEvent published() {
        verify(outbox).publish(eq(Topics.ACCOUNT_EVENTS), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("an opened account is announced on the account topic")
    void accountCreated() {
        producer.publishAccountCreated(7L, 42L, "CHECKING");

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.ACCOUNT_CREATED);
        assertThat(sent.partitionKey()).isEqualTo("7");
    }

    @Test
    @DisplayName("a balance change is keyed by account, so a consumer sees it in order")
    void balanceUpdated() {
        producer.publishBalanceUpdated(7L, 42L, new BigDecimal("120.00"), "DEPOSIT");

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.BALANCE_UPDATED);
        assertThat(sent.partitionKey()).isEqualTo("7");
    }

    @Test
    @DisplayName("an overdraft goes to the account topic, where its consumer listens")
    void overdraftTriggered() {
        producer.publishOverdraftTriggered(7L, 42L, new BigDecimal("40.00"));

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.OVERDRAFT_TRIGGERED);
        assertThat(sent.partitionKey()).isEqualTo("7");
    }

}
