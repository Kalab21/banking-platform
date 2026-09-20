package com.bankingplatform.user.kafka;

import com.bankingplatform.common.events.CreditCardTransactionCompleted;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.common.events.LoanDisbursed;
import com.bankingplatform.common.events.LoanPaidOff;
import com.bankingplatform.common.events.LoanRepaymentMade;
import com.bankingplatform.user.kafka.consumer.CreditCardEventConsumer;
import com.bankingplatform.user.kafka.consumer.LoanEventConsumer;
import com.bankingplatform.user.service.CreditScoreService;
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
 * Credit-score movements driven by events.
 *
 * <p>None of these had ever happened. Both consumers read a {@code userId}
 * that neither the card events nor the loan repayment event carried, and
 * returned on the null before reaching the switch — so no customer had ever
 * been rewarded for paying a card down or repaying a loan on time.
 *
 * <p>They also parsed the JSON themselves from a {@code String}, which is how
 * they came to spell field names independently of the producer.
 */
@DisplayName("Credit score event contracts")
class EventContractConsumptionTest {

    /**
     * Every delivery is the first one, so these keep testing the contract
     * rather than the de-duplication. Redelivery has its own tests.
     */
    private static final ProcessedEventGuard FIRST_DELIVERY = (consumer, eventId) -> true;


    private ObjectMapper mapper;
    private CreditScoreService creditScoreService;
    private CreditCardEventConsumer cardConsumer;
    private LoanEventConsumer loanConsumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        creditScoreService = Mockito.mock(CreditScoreService.class);
        cardConsumer = new CreditCardEventConsumer(creditScoreService, FIRST_DELIVERY);
        loanConsumer = new LoanEventConsumer(creditScoreService, FIRST_DELIVERY);
    }

    private DomainEvent overTheWire(DomainEvent published) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(published), DomainEvent.class);
    }

    @Test
    @DisplayName("paying a card down raises the score")
    void cardPaymentRaisesScore() throws Exception {
        cardConsumer.consume(overTheWire(CreditCardTransactionCompleted.of(
                5L, 42L, "tx-1", "PAYMENT", new BigDecimal("200.00"), new BigDecimal("1200.00"))));

        verify(creditScoreService).updateScore(42L, +5, "Credit card payment made");
    }

    @Test
    @DisplayName("spending on a card does not")
    void purchaseDoesNotRaiseScore() throws Exception {
        cardConsumer.consume(overTheWire(CreditCardTransactionCompleted.of(
                5L, 42L, "tx-2", "PURCHASE", new BigDecimal("200.00"), new BigDecimal("800.00"))));

        verifyNoInteractions(creditScoreService);
    }

    @Test
    @DisplayName("repaying and clearing a loan both raise the score")
    void loanEventsRaiseScore() throws Exception {
        loanConsumer.consume(overTheWire(LoanRepaymentMade.of(
                8L, 42L, "pay-1", new BigDecimal("250.00"), new BigDecimal("9750.00"))));
        loanConsumer.consume(overTheWire(LoanPaidOff.of(8L, 43L)));

        verify(creditScoreService).updateScore(42L, +5, "On-time loan repayment");
        verify(creditScoreService).updateScore(43L, +15, "Loan paid off in full");
    }

    @Test
    @DisplayName("a disbursement is not a score event")
    void disbursementIgnored() throws Exception {
        loanConsumer.consume(overTheWire(LoanDisbursed.of(8L, 42L, new BigDecimal("10000"), 3L)));

        verifyNoInteractions(creditScoreService);
    }

    /**
     * LOAN_PAYMENT_MISSED used to carry a -20 penalty here. Nothing produces
     * it — no job looks for a late instalment — so the branch is gone rather
     * than implying this platform detects delinquency.
     */
    @Test
    @DisplayName("a missed-payment event is no longer handled, because nothing emits one")
    void missedPaymentNotHandled() throws Exception {
        DomainEvent missed = mapper.readValue("""
                {"eventId":"abc","eventType":"LOAN_PAYMENT_MISSED","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z","loanId":8,"userId":42}
                """, DomainEvent.class);

        loanConsumer.consume(missed);

        verifyNoInteractions(creditScoreService);
    }

    @Test
    @DisplayName("an event type this service does not know is ignored")
    void unknownTypeIgnored() throws Exception {
        DomainEvent event = mapper.readValue("""
                {"eventId":"abc","eventType":"SOMETHING_ADDED_LATER","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z"}
                """, DomainEvent.class);

        cardConsumer.consume(event);
        loanConsumer.consume(event);

        verifyNoInteractions(creditScoreService);
    }
}
