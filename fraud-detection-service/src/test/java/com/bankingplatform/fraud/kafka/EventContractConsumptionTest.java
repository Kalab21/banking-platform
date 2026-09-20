package com.bankingplatform.fraud.kafka;

import com.bankingplatform.common.events.CreditCardTransactionCompleted;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.common.events.PaymentCompleted;
import com.bankingplatform.common.events.PaymentFailed;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import com.bankingplatform.fraud.service.FraudDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The fraud rules, fed by events that now carry what they read.
 *
 * <p>Both surviving rules had been dead. Transaction evaluation read a
 * {@code userId} the events never sent, and the failed-payment rule read a
 * {@code payerAccountId} the failure event never sent — and then guarded on
 * it being non-null, so it did nothing quietly rather than failing loudly.
 */
@DisplayName("Fraud event contracts")
class EventContractConsumptionTest {

    /**
     * Every delivery is the first one, so these keep testing the contract
     * rather than the de-duplication. Redelivery has its own tests.
     */
    private static final ProcessedEventGuard FIRST_DELIVERY = (consumer, eventId) -> true;


    private ObjectMapper mapper;
    private FraudDetectionService fraudService;
    private PlatformEventConsumer consumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        fraudService = Mockito.mock(FraudDetectionService.class);
        consumer = new PlatformEventConsumer(FIRST_DELIVERY, fraudService);
    }

    private DomainEvent overTheWire(DomainEvent published) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(published), DomainEvent.class);
    }

    @Test
    @DisplayName("a transaction is evaluated with both its account and its user")
    void transactionEvaluated() throws Exception {
        consumer.onTransactionEvent(overTheWire(TransactionCreated.of(
                1L, "ref-1", 3L, 42L, "WITHDRAWAL",
                new BigDecimal("9000.00"), new BigDecimal("100.00"))));

        verify(fraudService).evaluateTransaction(eq(3L), eq(42L), eq(new BigDecimal("9000.00")),
                eq("ref-1"), anyString());
    }

    @Test
    @DisplayName("a transfer is evaluated against the account the money left")
    void transferEvaluated() throws Exception {
        consumer.onTransactionEvent(overTheWire(TransferCompleted.of(
                "debit-1", "credit-1", 3L, 42L, 4L, new BigDecimal("500.00"))));

        verify(fraudService).evaluateTransaction(eq(3L), eq(42L), eq(new BigDecimal("500.00")),
                eq("debit-1"), anyString());
    }

    @Test
    @DisplayName("a failed payment is counted against the paying account")
    void failedPaymentCounted() throws Exception {
        // The signal this rule exists to catch, and it had never been counted.
        consumer.onPaymentEvent(overTheWire(PaymentFailed.of(1L, "pay-1", 3L, 42L)));

        verify(fraudService).evaluateFailedPayment(eq(3L), eq(42L), anyString());
    }

    @Test
    @DisplayName("a completed payment is not a fraud signal")
    void completedPaymentIgnored() throws Exception {
        consumer.onPaymentEvent(overTheWire(PaymentCompleted.of(
                1L, "pay-1", 3L, 42L, 4L, new BigDecimal("30.00"), "INTERNAL")));

        verifyNoInteractions(fraudService);
    }

    /**
     * There is no listener on credit-card-events any more. It read an
     * accountId a card transaction does not have, and its path ends in
     * freezing a deposit account because of a card purchase. Removed rather
     * than left looking like a working control.
     */
    @Test
    @DisplayName("a card transaction reaches no fraud rule, and that is deliberate")
    void cardTransactionNotEvaluated() throws Exception {
        DomainEvent card = overTheWire(CreditCardTransactionCompleted.of(
                5L, 42L, "tx-1", "PURCHASE", new BigDecimal("9000.00"), new BigDecimal("100.00")));

        consumer.onTransactionEvent(card);
        consumer.onPaymentEvent(card);

        verifyNoInteractions(fraudService);
    }

    /**
     * fraud_alerts.account_id and fraud_rules_audit.account_id are both
     * NOT NULL, and a null account would also collapse every such event onto
     * one Redis velocity key, mixing unrelated customers into one counter.
     */
    @Test
    @DisplayName("a transaction with no account is skipped rather than thrown out of the listener")
    void nullAccountIsSkipped() throws Exception {
        String legacy = """
                {"eventId":"abc","eventType":"TRANSACTION_CREATED","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z","transactionRef":"r","userId":42,
                 "amount":9000.00}
                """;

        consumer.onTransactionEvent(mapper.readValue(legacy, DomainEvent.class));

        verifyNoInteractions(fraudService);
    }

    @Test
    @DisplayName("an event type this service does not know is ignored")
    void unknownTypeIgnored() throws Exception {
        DomainEvent event = mapper.readValue("""
                {"eventId":"abc","eventType":"SOMETHING_ADDED_LATER","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z"}
                """, DomainEvent.class);

        consumer.onTransactionEvent(event);
        consumer.onPaymentEvent(event);

        verifyNoInteractions(fraudService);
    }
}
