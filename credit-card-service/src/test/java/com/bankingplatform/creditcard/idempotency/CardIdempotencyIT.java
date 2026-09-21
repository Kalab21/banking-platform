package com.bankingplatform.creditcard.idempotency;

import com.bankingplatform.common.idempotency.IdempotencyException;
import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyOutcome;
import com.bankingplatform.common.idempotency.IdempotencyStatus;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.common.security.Role;
import com.bankingplatform.creditcard.exception.InsufficientCreditException;
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
 * A retried card purchase charges the card once.
 *
 * <p>Against a real PostgreSQL, because the control is a unique constraint:
 * two requests carrying one key both attempt the insert and the database
 * picks the winner. A mocked store would only show that it was asked.
 *
 * <p>The card operation itself is stood in for by a counter. What is under
 * test here is how many times the operation is allowed to run, not the
 * arithmetic it performs — that is {@code CardConcurrencyIT}.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// The guard commits its claim before the operation runs, so a test-managed
// transaction that rolls back at the end would hide those commits from the
// second thread and prove nothing.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Card idempotency — PostgreSQL integration")
class CardIdempotencyIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credit_card_db")
            .withUsername("bankingadmin")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final CallerIdentity CALLER = new CallerIdentity(42L, "customer42", Role.CUSTOMER);
    private static final String PURCHASE = "PURCHASE";

    @Autowired private JdbcTemplate jdbc;

    private IdempotencyStore store;
    private IdempotencyGuard guard;
    private AtomicInteger charges;

    @BeforeEach
    void setUp() {
        store = new IdempotencyStore(jdbc);
        guard = new IdempotencyGuard(store, new ObjectMapper().registerModule(new JavaTimeModule()),
                new CardOutcomeClassifier());
        charges = new AtomicInteger();
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static String newKey() {
        return "card-" + UUID.randomUUID();
    }

    /** The request as the controller fingerprints it: body plus the card. */
    private static Map<String, Object> request(long cardId, String amount) {
        return Map.of("cardId", cardId, "request", Map.of("amount", new BigDecimal(amount)));
    }

    private ResponseEntity<Receipt> charge(String key, Map<String, Object> request) {
        return guard.execute(key, PURCHASE, CALLER, request, Receipt.class, Receipt::ref,
                () -> {
                    charges.incrementAndGet();
                    return new Receipt("tx-" + charges.get(), new BigDecimal("100.00"));
                });
    }

    /** Stands in for the card transaction the real operation returns. */
    public record Receipt(String ref, BigDecimal amount) {
    }

    @Test
    @DisplayName("the same purchase retried under one key charges the card once")
    void retryChargesOnce() {
        String key = newKey();
        Map<String, Object> request = request(1L, "100.00");

        ResponseEntity<Receipt> first = charge(key, request);
        ResponseEntity<Receipt> replay = charge(key, request);

        assertThat(charges).hasValue(1);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getFirst(IdempotencyGuard.REPLAY_HEADER))
                .as("the caller is told this was served rather than executed")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("concurrent duplicates charge the card once")
    void concurrentDuplicatesChargeOnce() throws Exception {
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
                    charge(key, request);
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

        assertThat(charges).as("eight requests, one charge").hasValue(1);
    }

    @Test
    @DisplayName("two purchases on different cards under one key is refused, not merged")
    void theCardIsPartOfTheFingerprint() {
        // The card id is a path variable rather than a body field. A
        // fingerprint over the body alone would make these two look like the
        // same request, and the second card would be served the first card's
        // receipt and never charged at all.
        String key = newKey();
        charge(key, request(1L, "100.00"));

        assertThatThrownBy(() -> charge(key, request(2L, "100.00")))
                .isInstanceOf(IdempotencyException.class);
        assertThat(charges).hasValue(1);
    }

    @Test
    @DisplayName("a purchase refused for want of credit releases the key")
    void refusalReleasesTheKey() {
        // Nothing was charged and nothing downstream was called, so the client
        // may pay down the card and retry under the same key. Caching the
        // refusal would make that impossible.
        String key = newKey();
        Map<String, Object> request = request(1L, "100.00");

        assertThatThrownBy(() -> guard.execute(key, PURCHASE, CALLER, request, Receipt.class,
                Receipt::ref, () -> {
                    throw new InsufficientCreditException("Insufficient credit");
                })).isInstanceOf(InsufficientCreditException.class);

        assertThat(store.find(key)).get()
                .extracting(IdempotencyOutcome::status)
                .isEqualTo(IdempotencyStatus.FAILED);

        ResponseEntity<Receipt> retried = charge(key, request);
        assertThat(charges).hasValue(1);
        assertThat(retried.getBody()).isNotNull();
    }

    @Test
    @DisplayName("a purchase whose effect is unknown is never retried under that key")
    void unknownOutcomeIsTerminal() {
        // A 5xx from account-service on a cash advance: the card side rolled
        // back, the account side may not have. Retrying would be a coin-flip
        // between a no-op and a second movement, so the key stays spent.
        String key = newKey();
        Map<String, Object> request = request(1L, "100.00");

        assertThatThrownBy(() -> guard.execute(key, PURCHASE, CALLER, request, Receipt.class,
                Receipt::ref, () -> {
                    // Deliberately not one of the locally-refused types: an
                    // IllegalStateException here is makePayment saying "no
                    // balance to pay", which moved nothing and releases the
                    // key. This is the other case — the call left and the
                    // answer never came back.
                    throw new RuntimeException("account-service answered 503");
                })).isInstanceOf(RuntimeException.class);

        assertThat(store.find(key)).get()
                .extracting(IdempotencyOutcome::status)
                .isEqualTo(IdempotencyStatus.UNKNOWN);

        assertThatThrownBy(() -> charge(key, request))
                .isInstanceOf(IdempotencyException.class);
        assertThat(charges).as("nothing ran under a spent key").hasValue(0);
    }

    @Test
    @DisplayName("the card service's own migration holds what the shared store writes")
    void schemaHoldsWhatTheStoreWrites() {
        // Flyway applies credit-card-service's V5 to an empty database here,
        // so the shared store and this service's table cannot drift apart
        // without this failing.
        String key = newKey();
        assertThat(store.claim(key, PURCHASE, "d".repeat(64))).isEmpty();
        store.complete(key, 201, "{\"ref\":\"tx-1\"}", "tx-1");

        assertThat(store.find(key)).get().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(PURCHASE);
            assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(record.responseStatus()).isEqualTo(201);
            assertThat(record.resultRef()).isEqualTo("tx-1");
        });
    }
}
