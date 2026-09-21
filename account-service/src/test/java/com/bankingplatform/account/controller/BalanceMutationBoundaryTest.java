package com.bankingplatform.account.controller;

import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import com.bankingplatform.account.idempotency.BalanceOutcomeClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.bankingplatform.account.dto.AccountResponse;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.service.AccountService;
import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The boundary between customer-facing and service-to-service balance changes.
 *
 * <p>Direct balance mutation used to sit on {@code /api/accounts/{id}/balance},
 * which the gateway routes. Any authenticated customer could therefore credit
 * their own account by an arbitrary amount, or debit another customer's. It now
 * exists only under {@code /internal}, which the gateway does not route.
 */
@DisplayName("Balance mutation boundary")
class BalanceMutationBoundaryTest {

    private static final long CUSTOMER_A = 10L;
    private static final long ACCOUNT_OF_A = 1L;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = Mockito.mock(AccountService.class);
        AccountResponse account = new AccountResponse();
        account.setId(ACCOUNT_OF_A);
        account.setUserId(CUSTOMER_A);
        account.setBalance(new BigDecimal("100.00"));
        when(accountService.updateBalance(anyLong(), any())).thenReturn(account);
        when(accountService.updateStatus(anyLong(), any())).thenReturn(account);
    }

    private MockMvc publicApi() {
        return MockMvcBuilders
                .standaloneSetup(new AccountController(accountService))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();
    }

    private MockMvc internalApi() {
        return MockMvcBuilders.standaloneSetup(new InternalAccountController(accountService, passThroughIdempotency(),
                            Mockito.mock(IdempotencyStore.class))).build();
    }

    @Nested
    @DisplayName("the public API no longer exposes balance mutation")
    class PublicSurface {

        @Test
        @DisplayName("crediting an account through the public path is not routed")
        void creditNotRoutedPublicly() throws Exception {
            publicApi().perform(put("/api/accounts/{id}/balance", ACCOUNT_OF_A)
                            .header(CallerIdentityHeaders.USER_ID, String.valueOf(CUSTOMER_A))
                            .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":1000000.00,\"operation\":\"CREDIT\"}"))
                    .andExpect(status().isNotFound());

            // The decisive assertion: a customer cannot mint money, because the
            // handler that would have done it is no longer reachable.
            verify(accountService, never()).updateBalance(anyLong(), any());
        }

        @Test
        @DisplayName("debiting another account through the public path is not routed")
        void debitNotRoutedPublicly() throws Exception {
            publicApi().perform(put("/api/accounts/{id}/balance", 999L)
                            .header(CallerIdentityHeaders.USER_ID, String.valueOf(CUSTOMER_A))
                            .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":50.00,\"operation\":\"DEBIT\"}"))
                    .andExpect(status().isNotFound());

            verify(accountService, never()).updateBalance(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("the internal API still serves the workflows that need it")
    class InternalSurface {

        @Test
        @DisplayName("a service-to-service debit succeeds, so transfers keep working")
        void internalDebitWorks() throws Exception {
            internalApi().perform(put("/internal/accounts/{id}/balance", ACCOUNT_OF_A)
                            .header(IdempotencyGuard.HEADER, "boundary-test-debit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":25.00,\"operation\":\"DEBIT\"}"))
                    .andExpect(status().isOk());

            verify(accountService).updateBalance(anyLong(), any());
        }

        @Test
        @DisplayName("a service-to-service credit succeeds, so disbursements keep working")
        void internalCreditWorks() throws Exception {
            internalApi().perform(put("/internal/accounts/{id}/balance", ACCOUNT_OF_A)
                            .header(IdempotencyGuard.HEADER, "boundary-test-credit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":25.00,\"operation\":\"CREDIT\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the internal endpoint needs no caller identity")
        void internalNeedsNoIdentity() throws Exception {
            // Fraud detection and the money-movement workflows call this from
            // Kafka listeners and service code, where there is no request to
            // derive an identity from.
            internalApi().perform(put("/internal/accounts/{id}/balance", ACCOUNT_OF_A)
                            .header(IdempotencyGuard.HEADER, "boundary-test-no-identity")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":10.00,\"operation\":\"CREDIT\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("fraud detection can still freeze an account without a caller")
        void internalFreezeWorks() throws Exception {
            // This path regressed when status became staff-only on the public
            // controller: the freeze runs from a Kafka listener and has no
            // identity, so it needed an internal route of its own.
            internalApi().perform(put("/internal/accounts/{id}/status", ACCOUNT_OF_A)
                            .param("status", AccountStatus.FROZEN.name()))
                    .andExpect(status().isOk());

            verify(accountService).updateStatus(ACCOUNT_OF_A, AccountStatus.FROZEN);
        }
    }

    /**
     * A guard whose store always hands out the key, so this test exercises
     * the boundary rather than replay. The idempotency rules themselves are
     * covered by BalanceIdempotencyIT.
     */
    private static IdempotencyGuard passThroughIdempotency() {
        IdempotencyStore store = Mockito.mock(IdempotencyStore.class);
        Mockito.when(store.claim(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(java.util.Optional.empty());
        return new IdempotencyGuard(store, new ObjectMapper().registerModule(new JavaTimeModule()),
                new BalanceOutcomeClassifier());
    }
}
