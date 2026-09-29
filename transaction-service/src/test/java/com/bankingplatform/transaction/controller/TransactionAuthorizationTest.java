package com.bankingplatform.transaction.controller;

import com.bankingplatform.transaction.idempotency.TransactionOutcomeClassifier;
import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.transaction.client.AccountClient;
import com.bankingplatform.transaction.dto.AccountResponse;
import com.bankingplatform.transaction.dto.TransactionResponse;
import com.bankingplatform.transaction.dto.TransferResponse;
import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import com.bankingplatform.transaction.security.AccountOwnershipVerifier;
import com.bankingplatform.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership authorization on money movement.
 *
 * <p>The defect these cover: {@code fromAccountId} arrived in the request body
 * and was used without ever being checked against the caller, so any
 * authenticated customer could transfer funds out of any account by supplying
 * its id.
 *
 * <p>Each denial also asserts that the transaction service was never called, so
 * a refused request cannot have moved money or written a transaction row.
 */
@DisplayName("Transaction authorization")
class TransactionAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;

    private static final long ACCOUNT_OF_A = 1L;
    private static final long ACCOUNT_OF_B = 2L;

    private TransactionService transactionService;
    private AccountClient accountClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        transactionService = Mockito.mock(TransactionService.class);
        accountClient = Mockito.mock(AccountClient.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new TransactionController(
                        transactionService, new AccountOwnershipVerifier(accountClient),
                        passThroughIdempotency()))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler(),
                        new com.bankingplatform.transaction.exception.GlobalExceptionHandler())
                .build();

        when(accountClient.getAccountById(ACCOUNT_OF_A)).thenReturn(account(ACCOUNT_OF_A, CUSTOMER_A));
        when(accountClient.getAccountById(ACCOUNT_OF_B)).thenReturn(account(ACCOUNT_OF_B, CUSTOMER_B));
        when(accountClient.getAccountInternal(ACCOUNT_OF_A)).thenReturn(account(ACCOUNT_OF_A, CUSTOMER_A));
        when(accountClient.getAccountInternal(ACCOUNT_OF_B)).thenReturn(account(ACCOUNT_OF_B, CUSTOMER_B));
        when(transactionService.deposit(any())).thenReturn(new TransactionResponse());
        when(transactionService.withdraw(any())).thenReturn(new TransactionResponse());
        when(transactionService.transfer(any())).thenReturn(transferResponse());
        // A concrete page rather than Page.empty(): the latter carries an
        // unpaged Pageable, which throws when Jackson serialises it.
        when(transactionService.getByAccountId(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
    }

    /**
     * An idempotency guard whose store always hands out the key, so these tests
     * exercise authorization rather than replay. The idempotency rules
     * themselves are covered by {@code TransactionIdempotencyTest}.
     */
    private static IdempotencyGuard passThroughIdempotency() {
        IdempotencyStore store = Mockito.mock(IdempotencyStore.class);
        when(store.claim(any(), any(), any())).thenReturn(Optional.empty());
        return new IdempotencyGuard(store, new ObjectMapper().registerModule(new JavaTimeModule()),
                new TransactionOutcomeClassifier());
    }

    private static TransferResponse transferResponse() {
        TransactionResponse debit = new TransactionResponse();
        debit.setTransactionRef("debit-ref");
        TransactionResponse credit = new TransactionResponse();
        credit.setTransactionRef("credit-ref");
        return TransferResponse.builder().debit(debit).credit(credit).build();
    }

    private static AccountResponse account(long accountId, long ownerId) {
        AccountResponse response = new AccountResponse();
        response.setId(accountId);
        response.setUserId(ownerId);
        response.setBalance(new BigDecimal("500.00"));
        return response;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, role)
                // Harmless on the reads; money movement requires one, and
                // sending it everywhere keeps these cases about ownership.
                .header(IdempotencyGuard.HEADER, "auth-test-" + userId + "-" + System.nanoTime());
    }

    private static String transferBody(long from, long to) {
        return "{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":25.00}".formatted(from, to);
    }

    @Nested
    @DisplayName("transfers")
    class Transfers {

        @Test
        @DisplayName("a customer may transfer from an account they own")
        void ownSourceAllowed() throws Exception {
            mvc.perform(as(post("/api/transactions/transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(ACCOUNT_OF_A, ACCOUNT_OF_B)))
                    .andExpect(status().isCreated());

            // Sending money to another customer is ordinary banking.
            verify(transactionService).transfer(any());
        }

        @Test
        @DisplayName("a customer cannot transfer out of another customer's account")
        void foreignSourceDenied() throws Exception {
            mvc.perform(as(post("/api/transactions/transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(ACCOUNT_OF_B, ACCOUNT_OF_A)))
                    .andExpect(status().isForbidden());

            // No debit, no credit, no transaction row.
            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a transfer to an account that does not exist is refused before anything is debited")
        void missingDestinationRefused() throws Exception {
            // The debit used to run first; the credit then failed and the
            // customer's money sat in a half-applied transfer.
            when(accountClient.getAccountInternal(999L)).thenThrow(org.mockito.Mockito.mock(feign.FeignException.NotFound.class));

            mvc.perform(as(post("/api/transactions/transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(ACCOUNT_OF_A, 999L)))
                    .andExpect(status().isUnprocessableEntity());

            verifyNoInteractions(transactionService);
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "a transfer into a {0} account is refused before anything is debited")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"FROZEN", "CLOSED"})
        void unusableDestinationRefused(String status) throws Exception {
            AccountResponse frozen = account(ACCOUNT_OF_B, CUSTOMER_B);
            frozen.setStatus(status);
            when(accountClient.getAccountInternal(ACCOUNT_OF_B)).thenReturn(frozen);

            mvc.perform(as(post("/api/transactions/transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(ACCOUNT_OF_A, ACCOUNT_OF_B)))
                    .andExpect(status().isUnprocessableEntity());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a transfer between accounts in different currencies is refused")
        void crossCurrencyRefused() throws Exception {
            AccountResponse usd = account(ACCOUNT_OF_A, CUSTOMER_A);
            usd.setCurrency("USD");
            AccountResponse eur = account(ACCOUNT_OF_B, CUSTOMER_B);
            eur.setCurrency("EUR");
            when(accountClient.getAccountById(ACCOUNT_OF_A)).thenReturn(usd);
            when(accountClient.getAccountInternal(ACCOUNT_OF_B)).thenReturn(eur);

            mvc.perform(as(post("/api/transactions/transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(ACCOUNT_OF_A, ACCOUNT_OF_B)))
                    .andExpect(status().isUnprocessableEntity());

            verifyNoInteractions(transactionService);
        }
    }

    /**
     * Regression tests: a request naming a currency the account does not hold
     * was applied in the account's currency and recorded under the name it
     * gave, so 100 "EUR" credited 100 USD and was stored as a EUR deposit.
     */
    @Nested
    @DisplayName("the currency of a request")
    class RequestCurrency {

        private void accountsIn(String currency) {
            AccountResponse a = account(ACCOUNT_OF_A, CUSTOMER_A);
            a.setCurrency(currency);
            when(accountClient.getAccountById(ACCOUNT_OF_A)).thenReturn(a);
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "a {0} naming another currency is refused")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"deposit", "withdraw"})
        void depositOrWithdrawalInAnotherCurrencyRefused(String operation) throws Exception {
            accountsIn("USD");
            mvc.perform(as(post("/api/transactions/" + operation), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":%d,\"amount\":100.00,\"currency\":\"EUR\"}".formatted(ACCOUNT_OF_A)))
                    .andExpect(status().isUnprocessableEntity());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a transfer naming another currency is refused")
        void transferInAnotherCurrencyRefused() throws Exception {
            accountsIn("USD");
            mvc.perform(as(post("/api/transactions/transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":25.00,\"currency\":\"EUR\"}"
                                    .formatted(ACCOUNT_OF_A, ACCOUNT_OF_B)))
                    .andExpect(status().isUnprocessableEntity());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a deposit naming no currency, or the account's in another case, is recorded in the account's")
        void depositIsRecordedInTheAccountsCurrency() throws Exception {
            accountsIn("USD");
            for (String body : List.of(
                    "{\"accountId\":%d,\"amount\":100.00}",
                    "{\"accountId\":%d,\"amount\":100.00,\"currency\":\"usd\"}")) {
                mvc.perform(as(post("/api/transactions/deposit"), CUSTOMER_A, "CUSTOMER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body.formatted(ACCOUNT_OF_A)))
                        .andExpect(status().is2xxSuccessful());
            }

            org.mockito.ArgumentCaptor<com.bankingplatform.transaction.dto.DepositRequest> sent =
                    org.mockito.ArgumentCaptor.forClass(com.bankingplatform.transaction.dto.DepositRequest.class);
            verify(transactionService, Mockito.times(2)).deposit(sent.capture());
            assertThat(sent.getAllValues()).extracting("currency").containsOnly("USD");
        }
    }

    @Nested
    @DisplayName("withdrawals and deposits")
    class WithdrawAndDeposit {

        @org.junit.jupiter.params.ParameterizedTest(name = "a {0} of a fraction of a cent is refused")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"deposit", "withdraw"})
        void subCentAmountsRefused(String operation) throws Exception {
            // The balance column holds cents, so the database rounded what it
            // was given: three deposits of 0.015 stored 0.06.
            mvc.perform(as(post("/api/transactions/" + operation), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":%d,\"amount\":0.015}".formatted(ACCOUNT_OF_A)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a transfer of a fraction of a cent is refused")
        void subCentTransferRefused() throws Exception {
            mvc.perform(as(post("/api/transactions/transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":0.015}"
                                    .formatted(ACCOUNT_OF_A, ACCOUNT_OF_B)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a customer may withdraw from their own account")
        void ownWithdrawAllowed() throws Exception {
            mvc.perform(as(post("/api/transactions/withdraw"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":%d,\"amount\":25.00}".formatted(ACCOUNT_OF_A)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("a customer cannot withdraw from another customer's account")
        void foreignWithdrawDenied() throws Exception {
            mvc.perform(as(post("/api/transactions/withdraw"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":%d,\"amount\":25.00}".formatted(ACCOUNT_OF_B)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a customer cannot deposit into another customer's account")
        void foreignDepositDenied() throws Exception {
            mvc.perform(as(post("/api/transactions/deposit"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":%d,\"amount\":25.00}".formatted(ACCOUNT_OF_B)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(transactionService);
        }
    }

    @Nested
    @DisplayName("transaction visibility")
    class Visibility {

        @Test
        @DisplayName("a customer may read their own account's history")
        void ownHistoryAllowed() throws Exception {
            mvc.perform(as(get("/api/transactions/account/{id}", ACCOUNT_OF_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read another customer's history")
        void foreignHistoryDenied() throws Exception {
            mvc.perform(as(get("/api/transactions/account/{id}", ACCOUNT_OF_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(transactionService, never()).getByAccountId(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot read a transaction reference belonging to another customer")
        void foreignReferenceDenied() throws Exception {
            TransactionResponse foreign = new TransactionResponse();
            foreign.setAccountId(ACCOUNT_OF_B);
            when(transactionService.getByRef("TXN-B")).thenReturn(foreign);

            // A reference is guessable, so possession of one must not be
            // treated as permission to read it.
            mvc.perform(as(get("/api/transactions/{ref}", "TXN-B"), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a customer may read a transaction reference on their own account")
        void ownReferenceAllowed() throws Exception {
            TransactionResponse own = new TransactionResponse();
            own.setAccountId(ACCOUNT_OF_A);
            when(transactionService.getByRef("TXN-A")).thenReturn(own);

            mvc.perform(as(get("/api/transactions/{ref}", "TXN-A"), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("a downstream refusal is reported as a refusal")
    class DownstreamRefusal {

        /** MockMvc including the service's own advice, which maps Feign errors. */
        private MockMvc withServiceAdvice() {
            return MockMvcBuilders
                    .standaloneSetup(new TransactionController(
                            transactionService, new AccountOwnershipVerifier(accountClient),
                            passThroughIdempotency()))
                    .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                    .setControllerAdvice(new CallerIdentityExceptionHandler(),
                            new com.bankingplatform.transaction.exception.GlobalExceptionHandler())
                    .build();
        }

        private FeignException forbidden() {
            Request request = Request.create(Request.HttpMethod.GET, "/api/accounts/2",
                    Map.of(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
            return FeignException.errorStatus("AccountClient#getAccountById",
                    feign.Response.builder()
                            .status(403)
                            .reason("Forbidden")
                            .request(request)
                            .headers(Map.of())
                            .build());
        }

        @Test
        @DisplayName("account-service refusing the lookup surfaces as 403, not 500")
        void downstreamForbiddenBecomes403() throws Exception {
            // account-service enforces ownership on the lookup itself. Without
            // an explicit mapping that refusal fell through to the catch-all
            // and was reported as a server error, making an enforced control
            // look like a bug.
            when(accountClient.getAccountById(ACCOUNT_OF_B)).thenThrow(forbidden());

            withServiceAdvice().perform(as(get("/api/transactions/account/{id}", ACCOUNT_OF_B),
                            CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(transactionService);
        }
    }

    @Nested
    @DisplayName("staff and fail-closed behaviour")
    class StaffAndFailClosed {

        @Test
        @DisplayName("staff cannot transfer money out of a customer's account")
        void staffCannotTransferFromACustomer() throws Exception {
            // Owner-or-staff let any employee send a customer's money to an
            // account of their choosing.
            mvc.perform(as(post("/api/transactions/transfer"), STAFF, "EMPLOYEE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(ACCOUNT_OF_B, ACCOUNT_OF_A)))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("staff cannot withdraw from a customer's account")
        void staffCannotWithdrawFromACustomer() throws Exception {
            mvc.perform(as(post("/api/transactions/withdraw"), STAFF, "ADMIN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":%d,\"amount\":25.00}".formatted(ACCOUNT_OF_B)))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("staff may read any account's history")
        void staffReadsAnyHistory() throws Exception {
            mvc.perform(as(get("/api/transactions/account/{id}", ACCOUNT_OF_B), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a request with no gateway identity is rejected and moves no money")
        void noIdentityRejected() throws Exception {
            mvc.perform(post("/api/transactions/transfer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(ACCOUNT_OF_A, ACCOUNT_OF_B)))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("even an admin role does not unlock a transfer out of another customer's account")
        void adminRoleDoesNotUnlockSomeoneElsesMoney() throws Exception {
            // This used to succeed: the admin role granted cross-customer
            // access to the source account, so the rule rested entirely on the
            // gateway never letting a role be forged. Money now leaves an
            // account only on its owner's instruction, whatever the role.
            mvc.perform(as(post("/api/transactions/transfer"), CUSTOMER_A, "ADMIN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(ACCOUNT_OF_B, ACCOUNT_OF_A)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(transactionService);
        }
    }
}
