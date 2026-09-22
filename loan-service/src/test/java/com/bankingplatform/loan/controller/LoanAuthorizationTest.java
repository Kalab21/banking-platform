package com.bankingplatform.loan.controller;

import com.bankingplatform.loan.dto.response.LoanRepaymentResponse;
import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import com.bankingplatform.loan.idempotency.LoanOutcomeClassifier;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.loan.dto.request.LoanRepaymentRequest;
import com.bankingplatform.loan.dto.response.LoanResponse;
import com.bankingplatform.loan.service.LoanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership authorization on loans.
 *
 * <p>loan-service had none. A loan id in a path was taken as sufficient
 * authority for the read or the repayment, so any signed-in customer could
 * fetch another customer's balance, rate and amortization schedule by guessing
 * a number, and could post repayments and payoffs against it.
 *
 * <p>Written at the HTTP boundary rather than against the service, because the
 * control under test is the boundary: a denial must return 403 <em>and</em> the
 * service must never be reached. Each negative case asserts both.
 */
@DisplayName("Loan authorization")
class LoanAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;
    private static final long LOAN_OF_A = 1L;

    private LoanService loanService;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        loanService = Mockito.mock(LoanService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new LoanController(loanService, passThroughIdempotency()))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder,
                                                    long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, role);
    }

    /** The stored loan is the authority on who owns it. */
    private void loanBelongsTo(long ownerUserId) {
        LoanResponse loan = new LoanResponse();
        loan.setId(LOAN_OF_A);
        loan.setUserId(ownerUserId);
        when(loanService.getLoan(LOAN_OF_A)).thenReturn(loan);
    }

    private String repayment() throws Exception {
        LoanRepaymentRequest request = new LoanRepaymentRequest();
        request.setAmount(new BigDecimal("100.00"));
        return json.writeValueAsString(request);
    }

    @Nested
    @DisplayName("reading a loan")
    class Reading {

        @Test
        @DisplayName("a customer reads their own loan")
        void ownLoanAllowed() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/loans/{id}", LOAN_OF_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read another customer's loan")
        void foreignLoanDenied() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/loans/{id}", LOAN_OF_A), CUSTOMER_B, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("staff may read any customer's loan")
        void staffReadsAny() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/loans/{id}", LOAN_OF_A), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an unidentified caller is refused")
        void anonymousDenied() throws Exception {
            mvc.perform(get("/api/loans/{id}", LOAN_OF_A))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("listing a customer's loans")
    class Listing {

        @Test
        @DisplayName("a customer lists their own loans")
        void ownListAllowed() throws Exception {
            mvc.perform(as(get("/api/loans/user/{id}", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot list another customer's loans")
        void foreignListDenied() throws Exception {
            mvc.perform(as(get("/api/loans/user/{id}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(loanService, never()).getLoansByUser(anyLong());
        }

        @Test
        @DisplayName("staff may list any customer's loans")
        void staffListsAny() throws Exception {
            mvc.perform(as(get("/api/loans/user/{id}", CUSTOMER_B), STAFF, "ADMIN"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("the schedule and repayment history")
    class RelatedReads {

        @Test
        @DisplayName("a customer cannot read another customer's amortization schedule")
        void foreignScheduleDenied() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/loans/{id}/schedule", LOAN_OF_A), CUSTOMER_B, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(loanService, never()).getAmortizationSchedule(anyLong());
        }

        @Test
        @DisplayName("a customer cannot read another customer's repayment history")
        void foreignRepaymentsDenied() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/loans/{id}/repayments", LOAN_OF_A), CUSTOMER_B, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(loanService, never()).getRepayments(anyLong());
        }

        @Test
        @DisplayName("a customer cannot read another customer's payoff quote")
        void foreignQuoteDenied() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/loans/{id}/payoff-quote", LOAN_OF_A), CUSTOMER_B, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(loanService, never()).getPayoffQuote(anyLong());
        }
    }

    @Nested
    @DisplayName("acting on a loan")
    class Writing {

        @Test
        @DisplayName("a customer cannot repay another customer's loan")
        void foreignRepaymentDenied() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/loans/{id}/repay", LOAN_OF_A), CUSTOMER_B, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(repayment()))
                    .andExpect(status().isForbidden());

            verify(loanService, never()).makeRepayment(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot pay off another customer's loan")
        void foreignPayoffDenied() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/loans/{id}/payoff", LOAN_OF_A), CUSTOMER_B, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(repayment()))
                    .andExpect(status().isForbidden());

            verify(loanService, never()).earlyPayoff(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot disburse another customer's loan")
        void foreignDisburseDenied() throws Exception {
            loanBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/loans/{id}/disburse", LOAN_OF_A), CUSTOMER_B, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"disbursementAccountId\":1}"))
                    .andExpect(status().isForbidden());

            verify(loanService, never()).disburseLoan(anyLong(), any());
        }

        @Test
        @DisplayName("a customer may repay their own loan")
        void ownRepaymentAllowed() throws Exception {
            loanBelongsTo(CUSTOMER_A);
            // The guard reads the payment reference off the result to record
            // against the key, so the service has to return something.
            LoanRepaymentResponse receipt = new LoanRepaymentResponse();
            receipt.setPaymentRef("repay-authorization-test");
            when(loanService.makeRepayment(anyLong(), any())).thenReturn(receipt);

            // Money movement now requires an Idempotency-Key, so a permitted
            // request has to carry one to get as far as the service.
            mvc.perform(as(post("/api/loans/{id}/repay", LOAN_OF_A), CUSTOMER_A, "CUSTOMER")
                            .header(IdempotencyGuard.HEADER, "loan-repay-authorization-test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(repayment()))
                    .andExpect(status().isCreated());

            verify(loanService).makeRepayment(anyLong(), any());
        }
    }

    /**
     * A loan is issued because an application was approved, never because
     * somebody asked for one. There is no create endpoint to authorise, which
     * is a stronger statement than a create endpoint that refuses: a role check
     * can be widened later by one line, and a route that does not exist cannot.
     */
    @Nested
    @DisplayName("issuing a loan directly")
    class DirectIssuance {

        private static final String TERMS =
                "{\"userId\":1,\"loanType\":\"PERSONAL_LOAN\",\"principal\":1000.00,"
                        + "\"interestRate\":6.0,\"termMonths\":12}";

        @Test
        @DisplayName("a customer cannot open a loan for themselves")
        void customerCannotCreate() throws Exception {
            mvc.perform(as(post("/api/loans"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TERMS))
                    .andExpect(status().isNotFound());

            verify(loanService, never()).createLoan(any());
        }

        @Test
        @DisplayName("a customer cannot open a loan in another customer's name")
        void customerCannotCreateForAnother() throws Exception {
            mvc.perform(as(post("/api/loans"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + CUSTOMER_B
                                    + ",\"loanType\":\"PERSONAL_LOAN\",\"principal\":1000.00,"
                                    + "\"interestRate\":6.0,\"termMonths\":12}"))
                    .andExpect(status().isNotFound());

            verify(loanService, never()).createLoan(any());
        }

        @Test
        @DisplayName("staff cannot choose a principal, a rate and a term either")
        void staffCannotCreate() throws Exception {
            // Staff review an application and decide an offer. They do not get
            // a side door into the product with terms of their own choosing.
            mvc.perform(as(post("/api/loans"), STAFF, "EMPLOYEE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TERMS))
                    .andExpect(status().isNotFound());

            verify(loanService, never()).createLoan(any());
        }
    }

    /**
     * An idempotency guard whose store always hands out the key, so these
     * tests exercise authorization rather than replay. The idempotency rules
     * themselves are covered by LoanIdempotencyIT.
     */
    private static IdempotencyGuard passThroughIdempotency() {
        IdempotencyStore store = Mockito.mock(IdempotencyStore.class);
        Mockito.when(store.claim(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(java.util.Optional.empty());
        return new IdempotencyGuard(store, new ObjectMapper().registerModule(new JavaTimeModule()),
                new LoanOutcomeClassifier());
    }
}
