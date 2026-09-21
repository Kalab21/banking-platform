package com.bankingplatform.loan.kafka.producer;

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
@DisplayName("Loan event producer")
class LoanEventProducerTest {

    @Mock
    private OutboxPublisher outbox;

    @InjectMocks
    private LoanEventProducer producer;

    @Captor
    private ArgumentCaptor<DomainEvent> event;

    private DomainEvent published() {
        verify(outbox).publish(eq(Topics.LOAN_EVENTS), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("a disbursement is keyed by loan")
    void disbursed() {
        producer.publishLoanDisbursed(4L, 42L, new BigDecimal("10000"), 9L);

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.LOAN_DISBURSED);
        assertThat(sent.partitionKey()).isEqualTo("4");
    }

    @Test
    @DisplayName("a repayment is keyed by loan, not by payment reference")
    void repaymentMade() {
        producer.publishRepaymentMade(4L, 42L, "rep-1", new BigDecimal("250.00"), new BigDecimal("9750.00"));

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.LOAN_REPAYMENT_MADE);
        assertThat(sent.partitionKey()).isEqualTo("4");
    }

    @Test
    @DisplayName("a payoff is announced, which is what closes the customer view")
    void paidOff() {
        producer.publishLoanPaidOff(4L, 42L);

        DomainEvent sent = published();
        assertThat(sent.eventType()).isEqualTo(EventTypes.LOAN_PAID_OFF);
        assertThat(sent.partitionKey()).isEqualTo("4");
    }

}
