package com.bankingplatform.loan.idempotency;

import com.bankingplatform.common.idempotency.IdempotencyException;
import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyOutcome;
import com.bankingplatform.common.idempotency.IdempotencyStatus;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.common.security.Role;
import com.bankingplatform.loan.exception.LoanNotActiveException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A retried repayment is applied once.
 *
 * <p>Against a real PostgreSQL, because the control is a unique constraint:
 * two requests carrying one key both attempt the insert and the database
 * picks the winner. A mocked store would only show that it was asked.
 *
 * <p>The repayment itself is stood in for by a counter. What is under test
 * here is how many times the operation is allowed to run, not the
 * arithmetic it performs — that is {@code LoanRepaymentConcurrencyIT}.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// The guard commits its claim before the operation runs, so a test-managed
// transaction that rolls back at the end would hide those commits from the
// second thread and prove nothing.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Loan idempotency — PostgreSQL integration")
class LoanIdempotencyGuardIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("loan_db")
            .withUsername("bankingadmin")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final CallerIdentity CALLER = new CallerIdentity(42L, "customer42", Role.CUSTOMER);
    private static final String REPAYMENT = "REPAYMENT";

    @Autowired private JdbcTemplate jdbc;

    private IdempotencyStore store;
    private IdempotencyGuard guard;
    private AtomicInteger applied;

    @BeforeEach
    void setUp() {
        store = new IdempotencyStore(jdbc);
        guard = new IdempotencyGuard(store, new ObjectMapper().registerModule(new JavaTimeModule()),
                new LoanOutcomeClassifier());
        applied = new AtomicInteger();
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static String newKey() {
        return "loan-" + UUID.randomUUID();
    }

    /** The request as the controller fingerprints it: body plus the loan. */
    private static Map<String, Object> request(long loanId, String amount) {
        return Map.of("loanId", loanId, "request", Map.of("amount", new BigDecimal(amount)));
    }

    private ResponseEntity<Receipt> repay(String key, Map<String, Object> request) {
        return guard.execute(key, REPAYMENT, CALLER, request, Receipt.class, Receipt::ref,
                () -> {
                    applied.incrementAndGet();
                    return new Receipt("rep-" + applied.get(), new BigDecimal("100.00"));
                });
    }

    /** Stands in for the repayment record the real operation returns. */
    public record Receipt(String ref, BigDecimal amount) {
    }

    @Test
    @DisplayName("the same repayment retried under one key is applied once")
    void retryAppliesOnce() {
        String key = newKey();
        Map<String, Object> request = request(1L, "100.00");

        ResponseEntity<Receipt> first = repay(key, request);
        ResponseEntity<Receipt> replay = repay(key, request);

        assertThat(applied).hasValue(1);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getFirst(IdempotencyGuard.REPLAY_HEADER))
                .as("the caller is told this was served rather than executed")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("concurrent duplicates are applied once")
    void concurrentDuplicatesApplyOnce() throws Exception {
        String key = newKey();
        Map<String, Object> request = request(1L, "100.00");

        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            attempts.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                try {
                    repay(key, request);
                } catch (IdempotencyException stillRunning) {
                    // A duplicate that waited out its budget. Acceptable: it
                    // executed nothing, which is the guarantee under test.
                }
                return null;
            });
        }
        pool.invokeAll(attempts);
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        assertThat(applied).as("eight requests, one repayment").hasValue(1);
    }

    @Test
    @DisplayName("two repayments on different loans under one key is refused, not merged")
    void theLoanIsPartOfTheFingerprint() {
        // The loan id is a path variable rather than a body field. A
        // fingerprint over the body alone would make these two look like the
        // same request, and the second loan would be served the first loan's
        // receipt and never be paid at all.
        String key = newKey();
        repay(key, request(1L, "100.00"));

        assertThatThrownBy(() -> repay(key, request(2L, "100.00")))
                .isInstanceOf(IdempotencyException.class);
        assertThat(applied).hasValue(1);
    }

    @Test
    @DisplayName("a repayment refused on an inactive loan releases the key")
    void refusalReleasesTheKey() {
        // Nothing was debited and nothing downstream was called, so the
        // client may fix the problem and retry under the same key. Caching
        // the refusal would make that impossible.
        String key = newKey();
        Map<String, Object> request = request(1L, "100.00");

        assertThatThrownBy(() -> guard.execute(key, REPAYMENT, CALLER, request, Receipt.class,
                Receipt::ref, () -> {
                    throw new LoanNotActiveException("Loan is not ACTIVE: PAID_OFF");
                })).isInstanceOf(LoanNotActiveException.class);

        assertThat(store.find(key)).get()
                .extracting(IdempotencyOutcome::status)
                .isEqualTo(IdempotencyStatus.FAILED);

        ResponseEntity<Receipt> retried = repay(key, request);
        assertThat(applied).hasValue(1);
        assertThat(retried.getBody()).isNotNull();
    }

    @Test
    @DisplayName("a repayment whose effect is unknown is never retried under that key")
    void unknownOutcomeIsTerminal() {
        // A 5xx from account-service on the debit: the loan side rolled
        // back, the account side may not have. Retrying would be a coin-flip
        // between a no-op and a second debit, so the key stays spent.
        String key = newKey();
        Map<String, Object> request = request(1L, "100.00");

        assertThatThrownBy(() -> guard.execute(key, REPAYMENT, CALLER, request, Receipt.class,
                Receipt::ref, () -> {
                    // Deliberately not one of the locally-refused types: an
                    // IllegalStateException here is "no pending payments
                    // found", which moved nothing and releases the key. This
                    // is the other case — the call left and the answer never
                    // came back.
                    throw new RuntimeException("account-service answered 503");
                })).isInstanceOf(RuntimeException.class);

        assertThat(store.find(key)).get()
                .extracting(IdempotencyOutcome::status)
                .isEqualTo(IdempotencyStatus.UNKNOWN);

        assertThatThrownBy(() -> repay(key, request))
                .isInstanceOf(IdempotencyException.class);
        assertThat(applied).as("nothing ran under a spent key").hasValue(0);
    }

    @Test
    @DisplayName("the loan service's own migration holds what the shared store writes")
    void schemaHoldsWhatTheStoreWrites() {
        // Flyway applies loan-service's V5 to an empty database here, so the
        // shared store and this service's table cannot drift apart without
        // this failing.
        String key = newKey();
        assertThat(store.claim(key, REPAYMENT, "d".repeat(64))).isEmpty();
        store.complete(key, 201, "{\"ref\":\"rep-1\"}", "rep-1");

        assertThat(store.find(key)).get().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(REPAYMENT);
            assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(record.responseStatus()).isEqualTo(201);
            assertThat(record.resultRef()).isEqualTo("rep-1");
        });
    }
}
