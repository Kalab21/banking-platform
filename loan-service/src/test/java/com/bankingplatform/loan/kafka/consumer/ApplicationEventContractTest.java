package com.bankingplatform.loan.kafka.consumer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.ApplicationRejected;
import com.bankingplatform.common.events.ApplicationSubmitted;
import com.bankingplatform.common.events.DomainEvent;
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

    private ObjectMapper mapper;
    private LoanService loanService;
    private ApplicationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        loanService = Mockito.mock(LoanService.class);
        consumer = new ApplicationEventConsumer(loanService);
    }

    private DomainEvent overTheWire(DomainEvent published) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(published), DomainEvent.class);
    }

    @Test
    @DisplayName("an approved loan application creates a loan with the approved terms")
    void approvedCreatesLoan() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "PERSONAL_LOAN", null, 760, new BigDecimal("12000"))));

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
    @DisplayName("a card approval belongs to another service")
    void cardApprovalIgnored() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "CREDIT_CARD", null, 760, new BigDecimal("5000"))));

        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("a submitted or rejected application issues nothing")
    void onlyApprovalIssues() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationSubmitted.of(7L, 42L, "PERSONAL_LOAN")));
        consumer.onApplicationEvent(overTheWire(ApplicationRejected.of(7L, 42L, "PERSONAL_LOAN", "low")));

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
