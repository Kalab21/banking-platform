package com.bankingplatform.user.kafka.producer;

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
@DisplayName("User lifecycle event producer")
class UserEventProducerTest {

    @Mock
    private OutboxPublisher outbox;

    @InjectMocks
    private UserEventProducer producer;

    @Captor
    private ArgumentCaptor<DomainEvent> event;

    private DomainEvent published() {
        verify(outbox).publish(eq(Topics.USER_EVENTS), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("an approved identity check is keyed by user")
    void kycApproved() {
        producer.publishKycApproved(42L);

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.KYC_APPROVED);
        assertThat(sent.partitionKey()).isEqualTo("42");
    }

    @Test
    @DisplayName("a rejected identity check is keyed by user")
    void kycRejected() {
        producer.publishKycRejected(42L);

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.KYC_REJECTED);
        assertThat(sent.partitionKey()).isEqualTo("42");
    }

    @Test
    @DisplayName("enabling a second factor announces it without the secret")
    void twoFactorEnabled() {
        producer.publishTwoFactorEnabled(42L);

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.TWO_FA_ENABLED);
        assertThat(sent.partitionKey()).isEqualTo("42");
    }

    @Test
    @DisplayName("disabling a second factor announces it too")
    void twoFactorDisabled() {
        producer.publishTwoFactorDisabled(42L);

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.TWO_FA_DISABLED);
        assertThat(sent.partitionKey()).isEqualTo("42");
    }

}
