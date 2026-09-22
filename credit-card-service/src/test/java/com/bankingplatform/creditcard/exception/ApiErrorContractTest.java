package com.bankingplatform.creditcard.exception;

import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import com.bankingplatform.creditcard.idempotency.CardOutcomeClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.bankingplatform.creditcard.controller.CreditCardController;
import com.bankingplatform.creditcard.service.CreditCardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP contract of the shared error handling.
 *
 * <p>A caller sending a bad request should be told it was bad. Before these
 * handlers existed, an unparseable body or an unknown enum value fell through
 * to the catch-all and came back as {@code 500}, which reads as "the server is
 * broken" when the truth is "your request was wrong" — and which also hides a
 * real fault when one does occur.
 */
@DisplayName("API error contract")
class ApiErrorContractTest {

    private static final long CARD = 5L;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new CreditCardController(Mockito.mock(CreditCardService.class), passThroughIdempotency()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("client mistakes are reported as client mistakes")
    class ClientErrors {

        @Test
        @DisplayName("an unknown enum value is 400, not 500")
        void unknownEnumValueIsBadRequest() throws Exception {
            mvc.perform(put("/api/credit-cards/{id}/status", CARD)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status":"VISA"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("a syntactically broken body is 400")
        void malformedJsonIsBadRequest() throws Exception {
            mvc.perform(post("/api/credit-cards/{id}/purchase", CARD)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":1,"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed request body"));
        }

        @Test
        @DisplayName("a path variable of the wrong type is 400, naming the parameter")
        void typeMismatchIsBadRequest() throws Exception {
            mvc.perform(get("/api/credit-cards/not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("cardId")));
        }

        @Test
        @DisplayName("a path with no handler is 404, not 500")
        void unknownPathIsNotFound() throws Exception {
            // A mistyped URL used to reach the catch-all and come back as a
            // server error, which reads like a fault worth probing.
            mvc.perform(get("/api/credit-cards/nope/zzz"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("No such endpoint"));
        }

        @Test
        @DisplayName("the wrong HTTP verb is 405, not 500")
        void wrongMethodIsMethodNotAllowed() throws Exception {
            // A path that exists for POST and not for GET. `/api/credit-cards`
            // itself is no longer such a path: nothing is mapped there at all
            // since a card stopped being something a caller may create, so it
            // answers 404 and would be testing the wrong handler.
            mvc.perform(get("/api/credit-cards/{id}/purchase", CARD))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.message").value(containsString("GET")));
        }
    }

    @Nested
    @DisplayName("error bodies do not leak internals")
    class NoLeakage {

        @Test
        @DisplayName("a malformed body never echoes the parser's own exception detail")
        void malformedBodyDoesNotLeakParserDetail() throws Exception {
            mvc.perform(put("/api/credit-cards/{id}/status", CARD)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"NOPE\"}"))
                    .andExpect(status().isBadRequest())
                    // No Jackson/Spring type names, and no list of valid enum
                    // constants, which would describe the internal model.
                    .andExpect(jsonPath("$.message").value(not(containsString("Exception"))))
                    .andExpect(jsonPath("$.message").value(not(containsString("com.bankingplatform"))));
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
