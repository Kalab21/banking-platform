package com.bankingplatform.loan.kafka.consumer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.ApplicationRejected;
import com.bankingplatform.common.events.ApplicationSubmitted;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.model.LoanType;
import com.bankingplatform.loan.service.LoanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issuing a loan from an approval.
 *
 * <p>This consumer creates a financial product, so what it reads out of the
 * event decides the principal, the rate and the term of a real loan. It used
 * to read them from a map by name; it reads the shared type now, and these
 * cases go through JSON so a producer change breaks the test.
 */
@DisplayName("Loan issuance event contract")
class ApplicationEventContractTest {

    /**
     * Every delivery is the first one, so these keep testing the contract
     * rather than the de-duplication. Redelivery has its own tests.
     */
    private static final ProcessedEventGuard FIRST_DELIVERY = (consumer, eventId) -> true;


    private ObjectMapper mapper;
    private LoanService loanService;
    private ApplicationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        loanService = Mockito.mock(LoanService.class);
        consumer = new ApplicationEventConsumer(loanService, FIRST_DELIVERY);
    }

    private DomainEvent overTheWire(DomainEvent published) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(published), DomainEvent.class);
    }

    @Test
    @DisplayName("an approved loan application creates a loan with the approved terms")
    void approvedCreatesLoan() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "PERSONAL_LOAN", null, 760, new BigDecimal("12000"), new BigDecimal("12000"))));

        ArgumentCaptor<CreateLoanRequest> captor = ArgumentCaptor.forClass(CreateLoanRequest.class);
        verify(loanService).createLoan(captor.capture());

        CreateLoanRequest request = captor.getValue();
        assertThat(request.getUserId()).isEqualTo(42L);
        assertThat(request.getApplicationId()).isEqualTo(7L);
        assertThat(request.getLoanType()).isEqualTo(LoanType.PERSONAL_LOAN);
        assertThat(request.getPrincipal()).isEqualByComparingTo(new BigDecimal("12000"));
        // The score came through, so the better rate was applied.
        assertThat(request.getInterestRate()).isEqualByComparingTo(new BigDecimal("10.99"));
    }

    @Test
    @DisplayName("a loan is written for the approved amount, not the requested one")
    void fundsTheApprovedAmount() throws Exception {
        // The customer asked for 10,000 and the bank agreed to 8,000. Writing
        // the loan for 10,000 would lend two thousand nobody approved.
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "PERSONAL_LOAN", null, 760, new BigDecimal("10000"), new BigDecimal("8000"))));

        ArgumentCaptor<CreateLoanRequest> captor = ArgumentCaptor.forClass(CreateLoanRequest.class);
        verify(loanService).createLoan(captor.capture());

        assertThat(captor.getValue().getPrincipal()).isEqualByComparingTo(new BigDecimal("8000"));
    }

    @Test
    @DisplayName("the loan takes the offered term and rate, not ones worked out here")
    void usesOfferedTerms() throws Exception {
        // The customer asked for 12 months. The offer was 36 at 10.99%. This
        // service used to pick 48 from a switch on product type.
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "PERSONAL_LOAN", null, 760,
                new BigDecimal("10000"), new BigDecimal("8000"),
                new BigDecimal("10.99"), 36, null, null)));

        ArgumentCaptor<CreateLoanRequest> captor = ArgumentCaptor.forClass(CreateLoanRequest.class);
        verify(loanService).createLoan(captor.capture());

        CreateLoanRequest request = captor.getValue();
        assertThat(request.getPrincipal()).isEqualByComparingTo(new BigDecimal("8000"));
        assertThat(request.getTermMonths()).isEqualTo(36);
        assertThat(request.getInterestRate()).isEqualByComparingTo(new BigDecimal("10.99"));
    }

    @Test
    @DisplayName("a payload with no approved amount falls back to the requested one")
    void fallsBackToRequestedAmount() throws Exception {
        // Version 1 of the event carried only the requested amount, and that
        // is what it meant by the amount to lend. A payload written before
        // this change must still issue the right loan.
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "PERSONAL_LOAN", null, 760, new BigDecimal("9000"), null)));

        ArgumentCaptor<CreateLoanRequest> captor = ArgumentCaptor.forClass(CreateLoanRequest.class);
        verify(loanService).createLoan(captor.capture());

        assertThat(captor.getValue().getPrincipal()).isEqualByComparingTo(new BigDecimal("9000"));
    }

    @Test
    @DisplayName("a card approval belongs to another service")
    void cardApprovalIgnored() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "CREDIT_CARD", null, 760, new BigDecimal("5000"), null)));

        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("a submitted or rejected application issues nothing")
    void onlyApprovalIssues() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationSubmitted.of(7L, 42L, "PERSONAL_LOAN")));
        consumer.onApplicationEvent(overTheWire(ApplicationRejected.of(7L, 42L, "PERSONAL_LOAN", "low")));

        verifyNoInteractions(loanService);
    }

    /**
     * Set.of(...).contains(null) throws NullPointerException rather than
     * returning false, so an approval without a product type would have
     * become a poison record on this partition.
     */
    @Test
    @DisplayName("an approval with no product type is ignored rather than throwing")
    void nullProductTypeIgnored() throws Exception {
        consumer.onApplicationEvent(mapper.readValue("""
                {"eventId":"abc","eventType":"APPLICATION_APPROVED","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z","applicationId":7,"userId":42}
                """, DomainEvent.class));

        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("an approval with no applicant is dead-lettered, not dropped")
    void nullUserIsDeadLettered() throws Exception {
        DomainEvent noApplicant = mapper.readValue("""
                {"eventId":"abc","eventType":"APPLICATION_APPROVED","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z","applicationId":7,
                 "productType":"PERSONAL_LOAN","requestedAmount":10000}
                """, DomainEvent.class);

        // Throwing rather than logging: an approved application that cannot be
        // issued must not vanish. The exception carries it to the dead letter
        // topic, where it is kept and can be looked at. Committing the offset
        // would lose a real application silently.
        assertThatThrownBy(() -> consumer.onApplicationEvent(noApplicant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no applicant");

        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("an event type this service does not know is ignored")
    void unknownTypeIgnored() throws Exception {
        consumer.onApplicationEvent(mapper.readValue("""
                {"eventId":"abc","eventType":"SOMETHING_ADDED_LATER","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z"}
                """, DomainEvent.class));

        verifyNoInteractions(loanService);
    }
}
