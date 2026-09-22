package com.bankingplatform.statistics.kafka;

import com.bankingplatform.common.events.AccountCreated;
import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.ApplicationRejected;
import com.bankingplatform.common.events.ApplicationSubmitted;
import com.bankingplatform.common.events.CreditCardCreated;
import com.bankingplatform.common.events.CreditCardTransactionCompleted;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.common.events.LoanDisbursed;
import com.bankingplatform.common.events.LoanPaidOff;
import com.bankingplatform.common.events.LoanRepaymentMade;
import com.bankingplatform.common.events.PaymentCompleted;
import com.bankingplatform.common.events.PaymentFailed;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.statistics.service.StatisticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The counters that were counting nothing.
 *
 * <p>Each case serializes the producer's own event and deserializes it the
 * way this service's Kafka configuration does, so a producer that drops a
 * field breaks the test rather than the statistic.
 */
@DisplayName("Statistics event contracts")
class EventContractConsumptionTest {

    /**
     * Every delivery is the first one, so these keep testing the contract
     * rather than the de-duplication. Redelivery has its own tests.
     */
    private static final ProcessedEventGuard FIRST_DELIVERY = (consumer, eventId) -> true;


    private ObjectMapper mapper;
    private StatisticsService statisticsService;
    private PlatformEventConsumer consumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        statisticsService = Mockito.mock(StatisticsService.class);
        consumer = new PlatformEventConsumer(FIRST_DELIVERY, statisticsService);
    }

    private DomainEvent overTheWire(DomainEvent published) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(published), DomainEvent.class);
    }

    @Test
    @DisplayName("a transaction is attributed to the user who made it")
    void transactionNamesUser() throws Exception {
        // The event carried no userId, so every transaction was counted
        // against a null user.
        consumer.onTransactionEvent(overTheWire(TransactionCreated.of(
                1L, "ref", 3L, 42L, "DEPOSIT", new BigDecimal("10.00"), new BigDecimal("110.00"))));

        verify(statisticsService).onTransactionCreated(42L, new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("a submitted application is counted, now that one is published")
    void submittedIsCounted() throws Exception {
        // publishApplicationSubmitted existed and was never called, so this
        // counter had always read zero.
        consumer.onApplicationEvent(overTheWire(ApplicationSubmitted.of(7L, 42L, "CREDIT_CARD")));

        verify(statisticsService).onApplicationSubmitted();
    }

    @Test
    @DisplayName("decided applications are still counted")
    void decisionsCounted() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "CREDIT_CARD", 8L, 700, new BigDecimal("5000"), null)));
        consumer.onApplicationEvent(overTheWire(ApplicationRejected.of(9L, 42L, "CREDIT_CARD", "low")));

        verify(statisticsService).onApplicationApproved();
        verify(statisticsService).onApplicationRejected();
    }

    @Test
    @DisplayName("accounts, cards, loans and payments are counted from their own events")
    void remainingCounters() throws Exception {
        consumer.onAccountEvent(overTheWire(AccountCreated.of(3L, 42L, "CHECKING")));
        consumer.onCreditCardEvent(overTheWire(CreditCardCreated.of(5L, 42L, "GOLD", "3823")));
        consumer.onCreditCardEvent(overTheWire(CreditCardTransactionCompleted.of(
                5L, 42L, "tx-1", "PURCHASE", new BigDecimal("20.00"), new BigDecimal("980.00"))));
        consumer.onLoanEvent(overTheWire(LoanDisbursed.of(8L, 42L, new BigDecimal("10000"), 3L)));
        consumer.onLoanEvent(overTheWire(LoanRepaymentMade.of(
                8L, 42L, "pay-1", new BigDecimal("250.00"), new BigDecimal("9750.00"))));
        consumer.onLoanEvent(overTheWire(LoanPaidOff.of(8L, 42L)));
        consumer.onPaymentEvent(overTheWire(PaymentCompleted.of(
                1L, "pay-1", 3L, 42L, 4L, new BigDecimal("30.00"), "INTERNAL")));
        consumer.onPaymentEvent(overTheWire(PaymentFailed.of(2L, "pay-2", 3L, 42L)));

        verify(statisticsService).onAccountCreated(42L);
        verify(statisticsService).onCreditCardCreated(42L);
        verify(statisticsService).onCcTransaction(5L, new BigDecimal("20.00"));
        verify(statisticsService).onLoanDisbursed(42L, new BigDecimal("10000"));
        verify(statisticsService).onLoanRepayment(new BigDecimal("250.00"));
        verify(statisticsService).onLoanPaidOff(42L);
        verify(statisticsService).onPaymentCompleted(3L, new BigDecimal("30.00"));
        verify(statisticsService).onPaymentFailed();
    }

    @Test
    @DisplayName("an event type this service does not know is ignored")
    void unknownTypeIgnored() throws Exception {
        DomainEvent event = mapper.readValue("""
                {"eventId":"abc","eventType":"SOMETHING_ADDED_LATER","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z"}
                """, DomainEvent.class);

        consumer.onAccountEvent(event);
        consumer.onTransactionEvent(event);
        consumer.onPaymentEvent(event);
        consumer.onApplicationEvent(event);
        consumer.onCreditCardEvent(event);
        consumer.onLoanEvent(event);

        verifyNoInteractions(statisticsService);
    }
}
