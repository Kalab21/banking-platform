package com.bankingplatform.transaction.idempotency;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.transaction.client.AccountClient;
import com.bankingplatform.transaction.controller.TransactionController;
import com.bankingplatform.transaction.dto.AccountResponse;
import com.bankingplatform.transaction.dto.TransactionResponse;
import com.bankingplatform.transaction.exception.AccountCallTimeoutException;
import com.bankingplatform.transaction.exception.GlobalExceptionHandler;
import com.bankingplatform.transaction.exception.TransferPartiallyAppliedException;
import com.bankingplatform.transaction.model.IdempotencyStatus;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code Idempotency-Key} on money movement.
 *
 * <p>These drive the real {@link IdempotencyGuard} against a stubbed store, so
 * they cover the decisions the guard makes — what counts as the same request,
 * which failures release the key, what a replay returns. The part that only a
 * database can settle, namely which of two simultaneous claims wins, is covered
 * by {@code IdempotentMoneyMovementIT} against a real PostgreSQL container.
 */
@DisplayName("Money-movement idempotency")
class TransactionIdempotencyTest {

    private static final long CUSTOMER = 10L;
    private static final long OTHER_CUSTOMER = 20L;
    private static final long OWN_ACCOUNT = 1L;
    private static final long FOREIGN_ACCOUNT = 2L;

    private static final String KEY = "client-key-0001";
    private static final String WITHDRAW_BODY = "{\"accountId\":1,\"amount\":25.00}";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private TransactionService transactionService;
    private AccountClient accountClient;
    private IdempotencyStore store;
    private MockMvc mvc;

    /** The fingerprint the guard computed, captured as it claims the key. */
    private final AtomicReference<String> claimedFingerprint = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        transactionService = Mockito.mock(TransactionService.class);
        accountClient = Mockito.mock(AccountClient.class);
        store = Mockito.mock(IdempotencyStore.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new TransactionController(
                        transactionService,
                        new AccountOwnershipVerifier(accountClient),
                        new IdempotencyGuard(store, mapper)))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler(), new GlobalExceptionHandler())
                .build();

        when(accountClient.getAccountById(OWN_ACCOUNT)).thenReturn(account(OWN_ACCOUNT, CUSTOMER));
        when(accountClient.getAccountById(FOREIGN_ACCOUNT)).thenReturn(account(FOREIGN_ACCOUNT, OTHER_CUSTOMER));
        when(transactionService.withdraw(any())).thenReturn(withdrawal("txn-ref-1"));

        // The default: the key is free, so the operation runs.
        when(store.claim(any(), any(), any())).thenAnswer(invocation -> {
            claimedFingerprint.set(invocation.getArgument(2));
            return Optional.empty();
        });
    }

    // ------------------------------------------------------------------ setup

    private static AccountResponse account(long accountId, long ownerId) {
        AccountResponse response = new AccountResponse();
        response.setId(accountId);
        response.setUserId(ownerId);
        response.setBalance(new BigDecimal("500.00"));
        return response;
    }

    private static TransactionResponse withdrawal(String ref) {
        TransactionResponse response = new TransactionResponse();
        response.setTransactionRef(ref);
        response.setAccountId(OWN_ACCOUNT);
        response.setAmount(new BigDecimal("25.00"));
        response.setBalanceAfter(new BigDecimal("475.00"));
        return response;
    }

    private static MockHttpServletRequestBuilder withdrawAs(long userId, String key, String body) {
        MockHttpServletRequestBuilder builder = post("/api/transactions/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER");
        return key == null ? builder : builder.header(IdempotencyGuard.HEADER, key);
    }

    /**
     * Builds the stored record from the fingerprint the guard itself computed,
     * so a "same request" case is genuinely the same request rather than a
     * duplicate of the hashing rules written out in the test.
     */
    private void storedRecord(IdempotencyStatus status, String body) {
        when(store.claim(any(), any(), any())).thenAnswer(invocation -> {
            claimedFingerprint.set(invocation.getArgument(2));
            return Optional.of(record(invocation.getArgument(2), status, body));
        });
    }

    private IdempotencyOutcome record(String fingerprint, IdempotencyStatus status, String body) {
        return new IdempotencyOutcome(1L, KEY, "WITHDRAWAL", fingerprint, status,
                body == null ? null : 201, body, null);
    }

    private static FeignException feign(int status, String reason) {
        Request request = Request.create(Request.HttpMethod.PUT, "/internal/accounts/1/balance",
                Map.of(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("AccountClient#updateBalance", feign.Response.builder()
                .status(status)
                .reason(reason)
                .request(request)
                .headers(Map.of())
                .build());
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("the key itself")
    class KeyContract {

        @Test
        @DisplayName("a money-movement request with no key is rejected and moves nothing")
        void missingKeyRejected() throws Exception {
            mvc.perform(withdrawAs(CUSTOMER, null, WITHDRAW_BODY))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionService);
            verifyNoInteractions(store);
        }

        @ParameterizedTest
        @DisplayName("a malformed key is rejected rather than stored")
        @ValueSource(strings = {
                "short",                     // below the minimum length
                "has spaces in it",          // outside the permitted alphabet
                "key\nwith-newline",         // would corrupt a log line
                "key/with/slashes"           // outside the permitted alphabet
        })
        void malformedKeyRejected(String key) throws Exception {
            mvc.perform(withdrawAs(CUSTOMER, key, WITHDRAW_BODY))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionService);
            verifyNoInteractions(store);
        }

        @Test
        @DisplayName("an over-long key is rejected, so the column cannot be overflowed")
        void oversizedKeyRejected() throws Exception {
            mvc.perform(withdrawAs(CUSTOMER, "k".repeat(256), WITHDRAW_BODY))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a well-formed key executes the operation once and records the result")
        void firstRequestExecutes() throws Exception {
            mvc.perform(withdrawAs(CUSTOMER, KEY, WITHDRAW_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionRef").value("txn-ref-1"));

            verify(transactionService).withdraw(any());
            verify(store).complete(eq(KEY), eq(201), anyString(), eq("txn-ref-1"));
        }
    }

    @Nested
    @DisplayName("replaying a key")
    class Replay {

        @Test
        @DisplayName("the same key and the same request return the original result without re-executing")
        void sameRequestReplaysOriginalResult() throws Exception {
            storedRecord(IdempotencyStatus.COMPLETED,
                    "{\"transactionRef\":\"txn-ref-1\",\"accountId\":1,\"amount\":25.00}");

            mvc.perform(withdrawAs(CUSTOMER, KEY, WITHDRAW_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(IdempotencyGuard.REPLAY_HEADER, "true"))
                    .andExpect(jsonPath("$.transactionRef").value("txn-ref-1"));

            // The point of the whole exercise: no second debit.
            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("an amount written with a different scale is the same request, not a conflict")
        void amountScaleDoesNotChangeTheRequest() throws Exception {
            storedRecord(IdempotencyStatus.COMPLETED,
                    "{\"transactionRef\":\"txn-ref-1\",\"accountId\":1,\"amount\":25.00}");

            // 25 and 25.00 are the same amount of money. A retry that
            // re-serialised its own payload must not be told it conflicts.
            mvc.perform(withdrawAs(CUSTOMER, KEY, "{\"accountId\":1,\"amount\":25}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(IdempotencyGuard.REPLAY_HEADER, "true"));

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("the same key with a different request is a conflict and executes nothing")
        void differentRequestConflicts() throws Exception {
            when(store.claim(any(), any(), any())).thenReturn(Optional.of(
                    record("a-fingerprint-of-some-other-request", IdempotencyStatus.COMPLETED,
                            "{\"transactionRef\":\"txn-ref-1\"}")));

            mvc.perform(withdrawAs(CUSTOMER, KEY, "{\"accountId\":1,\"amount\":9999.00}"))
                    .andExpect(status().isConflict());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("a duplicate that arrives mid-flight collects the original result")
        void concurrentDuplicateWaitsForTheOriginal() throws Exception {
            storedRecord(IdempotencyStatus.IN_PROGRESS, null);
            when(store.find(KEY)).thenAnswer(invocation -> Optional.of(record(
                    claimedFingerprint.get(), IdempotencyStatus.COMPLETED,
                    "{\"transactionRef\":\"txn-ref-1\"}")));

            mvc.perform(withdrawAs(CUSTOMER, KEY, WITHDRAW_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(IdempotencyGuard.REPLAY_HEADER, "true"))
                    .andExpect(jsonPath("$.transactionRef").value("txn-ref-1"));

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("an outcome that was never established is reported, never retried")
        void unknownOutcomeIsNotReplayedAsSuccess() throws Exception {
            storedRecord(IdempotencyStatus.UNKNOWN, null);

            // 504, not 201 and not a second debit. Whether the balance changed
            // is unknown, and both of the convenient answers would be a guess.
            mvc.perform(withdrawAs(CUSTOMER, KEY, WITHDRAW_BODY))
                    .andExpect(status().isGatewayTimeout());

            verifyNoInteractions(transactionService);
        }
    }

    @Nested
    @DisplayName("how a failure settles the key")
    class FailureSemantics {

        @Test
        @DisplayName("a refusal that applied nothing releases the key for a retry")
        void businessRefusalReleasesTheKey() throws Exception {
            // Insufficient funds: the account service declined rather than
            // applied. Caching that as an answer would leave the client unable
            // to retry the same operation after funding the account.
            when(transactionService.withdraw(any())).thenThrow(feign(422, "Insufficient funds"));

            mvc.perform(withdrawAs(CUSTOMER, KEY, WITHDRAW_BODY))
                    .andExpect(status().isUnprocessableEntity());

            verify(store).markFailed(KEY);
            verify(store, never()).complete(anyString(), anyInt(), anyString(), anyString());
        }

        @Test
        @DisplayName("a timeout spends the key, because the balance may already have changed")
        void timeoutSettlesUnknown() throws Exception {
            when(transactionService.withdraw(any()))
                    .thenThrow(new AccountCallTimeoutException("The account service did not respond in time"));

            mvc.perform(withdrawAs(CUSTOMER, KEY, WITHDRAW_BODY))
                    .andExpect(status().isGatewayTimeout());

            verify(store).markUnknown(KEY);
            verify(store, never()).markFailed(anyString());
        }

        @Test
        @DisplayName("a downstream 5xx spends the key rather than inviting a second debit")
        void serverErrorSettlesUnknown() throws Exception {
            when(transactionService.withdraw(any())).thenThrow(feign(500, "Internal Server Error"));

            mvc.perform(withdrawAs(CUSTOMER, KEY, WITHDRAW_BODY))
                    .andExpect(status().isInternalServerError());

            // A 5xx says the call was made and then went wrong. It does not say
            // the balance was left alone, so the key is not handed back.
            verify(store).markUnknown(KEY);
        }

        @Test
        @DisplayName("a half-applied transfer is reported as inconsistent and spends the key")
        void partiallyAppliedTransferSettlesUnknown() throws Exception {
            when(transactionService.transfer(any())).thenThrow(
                    new TransferPartiallyAppliedException("debit applied, credit did not", feign(422, "Frozen")));

            // The wrapped cause is a 4xx, which on its own would read as "the
            // account service declined and nothing moved". It did not: the
            // source was already debited, so neither a 422 to the caller nor a
            // reusable key would be true.
            mvc.perform(post("/api/transactions/transfer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":25.00}")
                            .header(CallerIdentityHeaders.USER_ID, String.valueOf(CUSTOMER))
                            .header(CallerIdentityHeaders.USERNAME, "user" + CUSTOMER)
                            .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER")
                            .header(IdempotencyGuard.HEADER, KEY))
                    .andExpect(status().isInternalServerError());

            verify(store).markUnknown(KEY);
            verify(store, never()).markFailed(anyString());
        }
    }

    @Nested
    @DisplayName("interaction with authorization")
    class AuthorizationPrecedence {

        @Test
        @DisplayName("a denied request claims no key and caches no result")
        void deniedRequestNeverReachesTheGuard() throws Exception {
            mvc.perform(withdrawAs(CUSTOMER, KEY, "{\"accountId\":2,\"amount\":25.00}"))
                    .andExpect(status().isForbidden());

            // Ownership is checked before the key is claimed, so a refused
            // caller cannot occupy someone's key, cannot learn whether it
            // exists, and cannot leave a successful-looking record behind.
            verifyNoInteractions(store);
            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("the caller is part of the request fingerprint")
        void fingerprintIsScopedToTheCaller() throws Exception {
            mvc.perform(withdrawAs(CUSTOMER, KEY, WITHDRAW_BODY)).andExpect(status().isCreated());
            String first = claimedFingerprint.get();

            when(accountClient.getAccountById(OWN_ACCOUNT))
                    .thenReturn(account(OWN_ACCOUNT, OTHER_CUSTOMER));
            mvc.perform(withdrawAs(OTHER_CUSTOMER, KEY, WITHDRAW_BODY)).andExpect(status().isCreated());

            // Same key, same body, different principal: a different request.
            // The stored result of one customer can never be handed to another
            // even if the ownership check above were somehow bypassed.
            assertThat(claimedFingerprint.get()).isNotEqualTo(first);
        }
    }
}
