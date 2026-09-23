package com.bankingplatform.creditcard.controller;

import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import com.bankingplatform.creditcard.idempotency.CardOutcomeClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.creditcard.dto.response.CreditCardResponse;
import com.bankingplatform.creditcard.dto.response.CreditCardTransactionResponse;
import com.bankingplatform.creditcard.service.CreditCardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership authorization on credit cards.
 *
 * <p>credit-card-service had none. A card id in a path was taken as sufficient
 * authority, so any signed-in customer could read another customer's balance,
 * limit, APR, rewards and full transaction history by guessing a number — and
 * could freeze the card, spend on it, take a cash advance against it or pay it
 * down.
 *
 * <p>Written at the HTTP boundary rather than against the service, because the
 * control under test is the boundary: a denial must return 403 <em>and</em> the
 * service must never be reached.
 */
@DisplayName("Credit card authorization")
class CreditCardAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;
    private static final long CARD_OF_A = 5L;

    private CreditCardService creditCardService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        creditCardService = Mockito.mock(CreditCardService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new CreditCardController(creditCardService, passThroughIdempotency()))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver(),
                        new PageableHandlerMethodArgumentResolver())
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

    /** The stored card is the authority on who owns it. */
    private void cardBelongsTo(long ownerUserId) {
        CreditCardResponse card = new CreditCardResponse();
        card.setId(CARD_OF_A);
        card.setUserId(ownerUserId);
        when(creditCardService.getCard(CARD_OF_A)).thenReturn(card);
    }

    @Nested
    @DisplayName("reading a card")
    class Reading {

        @Test
        @DisplayName("a customer reads their own card")
        void ownCardAllowed() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/credit-cards/{id}", CARD_OF_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read another customer's card")
        void foreignCardDenied() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/credit-cards/{id}", CARD_OF_A), CUSTOMER_B, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("staff may read any customer's card")
        void staffReadsAny() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/credit-cards/{id}", CARD_OF_A), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an unidentified caller is refused")
        void anonymousDenied() throws Exception {
            mvc.perform(get("/api/credit-cards/{id}", CARD_OF_A))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a customer cannot list another customer's cards")
        void foreignListDenied() throws Exception {
            mvc.perform(as(get("/api/credit-cards/user/{id}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(creditCardService, never()).getCardsByUser(anyLong());
        }

        @Test
        @DisplayName("a customer cannot read another customer's card transactions")
        void foreignTransactionsDenied() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/credit-cards/{id}/transactions", CARD_OF_A), CUSTOMER_B, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(creditCardService, never()).getTransactions(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot read another customer's statements")
        void foreignStatementsDenied() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/credit-cards/{id}/statements", CARD_OF_A), CUSTOMER_B, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(creditCardService, never()).getStatements(anyLong());
        }

        @Test
        @DisplayName("a transaction reference is authorised against the card behind it")
        void foreignTransactionRefDenied() throws Exception {
            cardBelongsTo(CUSTOMER_A);
            CreditCardTransactionResponse transaction = new CreditCardTransactionResponse();
            transaction.setCreditCardId(CARD_OF_A);
            when(creditCardService.getTransaction(anyString())).thenReturn(transaction);

            mvc.perform(as(get("/api/credit-cards/transactions/{ref}", "abc-123"), CUSTOMER_B, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("acting on a card")
    class Writing {

        @Test
        @DisplayName("a customer cannot freeze another customer's card")
        void foreignStatusDenied() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(put("/api/credit-cards/{id}/status", CARD_OF_A), CUSTOMER_B, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CUSTOMER_FROZEN\"}"))
                    .andExpect(status().isForbidden());

            verify(creditCardService, never()).updateStatus(anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("a customer cannot spend on another customer's card")
        void foreignPurchaseDenied() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/credit-cards/{id}/purchase", CARD_OF_A), CUSTOMER_B, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":50.00,\"description\":\"Example purchase\"}"))
                    .andExpect(status().isForbidden());

            verify(creditCardService, never()).purchase(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot take a cash advance on another customer's card")
        void foreignCashAdvanceDenied() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/credit-cards/{id}/cash-advance", CARD_OF_A), CUSTOMER_B, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":50.00,\"targetAccountId\":1}"))
                    .andExpect(status().isForbidden());

            verify(creditCardService, never()).cashAdvance(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot pay another customer's card")
        void foreignPaymentDenied() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/credit-cards/{id}/payment", CARD_OF_A), CUSTOMER_B, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":50.00}"))
                    .andExpect(status().isForbidden());

            verify(creditCardService, never()).makePayment(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot cut their own statement")
        void customerCannotGenerateStatement() throws Exception {
            // A statement is issued by the bank on a cycle. Left open, a
            // cardholder could produce as many billing periods as they liked.
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/credit-cards/{id}/statements/generate", CARD_OF_A),
                            CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(creditCardService, never()).generateStatement(anyLong());
        }

        @Test
        @DisplayName("staff may cut a statement")
        void staffMayGenerateStatement() throws Exception {
            cardBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/credit-cards/{id}/statements/generate", CARD_OF_A),
                            STAFF, "EMPLOYEE"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("a customer cannot issue a card for themselves")
        void customerCannotCreate() throws Exception {
            mvc.perform(as(post("/api/credit-cards"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + CUSTOMER_A
                                    + ",\"cardType\":\"PLATINUM\",\"creditLimit\":50000.00,\"apr\":0.99}"))
                    .andExpect(status().isNotFound());

            verify(creditCardService, never()).createCard(any());
        }

        @Test
        @DisplayName("a customer cannot issue a card in another customer's name")
        void foreignCreateDenied() throws Exception {
            mvc.perform(as(post("/api/credit-cards"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + CUSTOMER_B
                                    + ",\"cardType\":\"STANDARD\",\"creditLimit\":3000.00,\"apr\":21.99}"))
                    .andExpect(status().isNotFound());

            verify(creditCardService, never()).createCard(any());
        }

        @Test
        @DisplayName("staff cannot choose a tier, a limit and an APR either")
        void staffCannotCreate() throws Exception {
            // Staff review an application and decide an offer. They do not get
            // a side door into the product with terms of their own choosing.
            mvc.perform(as(post("/api/credit-cards"), STAFF, "EMPLOYEE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + CUSTOMER_A
                                    + ",\"cardType\":\"PLATINUM\",\"creditLimit\":50000.00,\"apr\":0.99}"))
                    .andExpect(status().isNotFound());

            verify(creditCardService, never()).createCard(any());
        }
    }

    /**
     * An idempotency guard whose store always hands out the key, so these
     * tests exercise authorization rather than replay. The idempotency rules
     * themselves are covered by CardIdempotencyIT.
     */
    private static IdempotencyGuard passThroughIdempotency() {
        IdempotencyStore store = Mockito.mock(IdempotencyStore.class);
        Mockito.when(store.claim(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(java.util.Optional.empty());
        return new IdempotencyGuard(store, new ObjectMapper().registerModule(new JavaTimeModule()),
                new CardOutcomeClassifier());
    }
}
