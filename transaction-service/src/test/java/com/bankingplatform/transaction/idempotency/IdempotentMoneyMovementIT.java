package com.bankingplatform.transaction.idempotency;

import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.common.security.Role;
import com.bankingplatform.transaction.dto.TransactionResponse;
import com.bankingplatform.transaction.dto.TransferRequest;
import com.bankingplatform.transaction.dto.TransferResponse;
import com.bankingplatform.transaction.exception.AccountCallTimeoutException;
import com.bankingplatform.transaction.exception.IdempotencyException;
import com.bankingplatform.transaction.exception.TransactionException;
import com.bankingplatform.transaction.model.IdempotencyRecord;
import com.bankingplatform.transaction.model.IdempotencyStatus;
import com.bankingplatform.transaction.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Idempotency against a real PostgreSQL database.
 *
 * <p>The guarantee being tested is a database guarantee. Two concurrent
 * requests carrying the same key both try to insert it; the unique constraint
 * picks a winner, and the loser resolves against the winner's row. A mocked
 * repository cannot demonstrate that — it would only demonstrate that the mock
 * was told what to return — so these run against the same PostgreSQL 16 the
 * service uses, with the Flyway migrations applied to an empty database.
 *
 * <p>The account service is stood in for by a counter and a balance held in
 * memory. What is under test here is how many times the operation is allowed to
 * run, not the arithmetic it performs.
 *
 * <p>Named {@code *IT} and bound to Failsafe, so {@code mvn test} skips it and
 * {@code mvn verify} runs it. Requires a working Docker daemon.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(IdempotentMoneyMovementIT.GuardUnderTest.class)
// The guard's whole design depends on committing the claim in its own
// transaction before the money moves. A test-managed transaction that rolls
// back at the end would hide those commits from the second thread and prove
// nothing, so this test runs outside one.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Money-movement idempotency — PostgreSQL integration")
class IdempotentMoneyMovementIT {

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("transaction_db")
                    .withUsername("bankingadmin")
                    .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class GuardUnderTest {

        @Bean
        IdempotencyStore idempotencyStore(IdempotencyRecordRepository repository) {
            return new IdempotencyStore(repository);
        }

        @Bean
        IdempotencyGuard idempotencyGuard(IdempotencyStore store) {
            return new IdempotencyGuard(store, new ObjectMapper().registerModule(new JavaTimeModule()));
        }
    }

    private static final CallerIdentity CALLER = new CallerIdentity(7L, "customer7", Role.CUSTOMER);

    @Autowired private IdempotencyGuard guard;
    @Autowired private IdempotencyRecordRepository repository;

    // ------------------------------------------------------------- fixtures

    /** A stand-in for the account service: counts executions and moves a balance. */
    private static final class Ledger {
        private final AtomicInteger executions = new AtomicInteger();
        private BigDecimal sourceBalance = new BigDecimal("100.00");
        private final long delayMillis;

        Ledger(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        synchronized TransferResponse apply(BigDecimal amount) {
            executions.incrementAndGet();
            sourceBalance = sourceBalance.subtract(amount);
            TransactionResponse debit = new TransactionResponse();
            debit.setTransactionRef("debit-" + executions.get());
            debit.setAmount(amount);
            debit.setBalanceAfter(sourceBalance);
            TransactionResponse credit = new TransactionResponse();
            credit.setTransactionRef("credit-" + executions.get());
            return TransferResponse.builder().debit(debit).credit(credit).build();
        }

        TransferResponse slowApply(BigDecimal amount) {
            // Held open so that the duplicate genuinely overlaps the original
            // rather than arriving after it has settled.
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return apply(amount);
        }
    }

    private static TransferRequest transfer(String amount) {
        TransferRequest request = new TransferRequest();
        request.setFromAccountId(1L);
        request.setToAccountId(2L);
        request.setAmount(new BigDecimal(amount));
        request.setDescription("rent");
        return request;
    }

    private ResponseEntity<TransferResponse> run(String key, TransferRequest request,
                                                 java.util.function.Supplier<TransferResponse> action) {
        return guard.execute(key, "TRANSFER", CALLER, request, TransferResponse.class,
                result -> result.getDebit().getTransactionRef(), action);
    }

    private static String newKey() {
        return "it-" + UUID.randomUUID();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("two simultaneous requests with one key produce one transfer, not two")
    void concurrentDuplicatesExecuteOnce() throws Exception {
        String key = newKey();
        TransferRequest request = transfer("40.00");
        Ledger ledger = new Ledger(400);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<ResponseEntity<TransferResponse>> attempt = () -> {
            startTogether.await(10, TimeUnit.SECONDS);
            return run(key, request, () -> ledger.slowApply(request.getAmount()));
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<TransferResponse>> first = pool.submit(attempt);
            Future<ResponseEntity<TransferResponse>> second = pool.submit(attempt);

            ResponseEntity<TransferResponse> a = first.get(30, TimeUnit.SECONDS);
            ResponseEntity<TransferResponse> b = second.get(30, TimeUnit.SECONDS);

            // One debit. This is the whole point.
            assertThat(ledger.executions).hasValue(1);
            assertThat(ledger.sourceBalance).isEqualByComparingTo("60.00");

            // Both callers are told the same thing, and it is the truth.
            assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(a.getBody().getDebit().getTransactionRef())
                    .isEqualTo(b.getBody().getDebit().getTransactionRef());

            // Exactly one of them executed; the other was served the result.
            List<String> replayHeaders = List.of(
                    String.valueOf(a.getHeaders().getFirst(IdempotencyGuard.REPLAY_HEADER)),
                    String.valueOf(b.getHeaders().getFirst(IdempotencyGuard.REPLAY_HEADER)));
            assertThat(replayHeaders).containsExactlyInAnyOrder("true", "null");
        } finally {
            pool.shutdownNow();
        }

        // One key, one row, one recorded outcome.
        assertThat(repository.findByIdempotencyKey(key))
                .get()
                .extracting(IdempotencyRecord::getStatus, IdempotencyRecord::getResultRef)
                .containsExactly(IdempotencyStatus.COMPLETED, "debit-1");
    }

    @Test
    @DisplayName("retrying a completed request returns the original result and moves nothing")
    void sequentialRetryReturnsTheOriginalResult() {
        String key = newKey();
        TransferRequest request = transfer("30.00");
        Ledger ledger = new Ledger(0);

        ResponseEntity<TransferResponse> original =
                run(key, request, () -> ledger.apply(request.getAmount()));
        ResponseEntity<TransferResponse> replayed =
                run(key, request, () -> ledger.apply(request.getAmount()));

        assertThat(ledger.executions).hasValue(1);
        assertThat(ledger.sourceBalance).isEqualByComparingTo("70.00");

        assertThat(replayed.getStatusCode()).isEqualTo(original.getStatusCode());
        assertThat(replayed.getBody().getDebit().getTransactionRef())
                .isEqualTo(original.getBody().getDebit().getTransactionRef());
        assertThat(replayed.getBody().getDebit().getBalanceAfter()).isEqualByComparingTo("70.00");
        assertThat(replayed.getHeaders().getFirst(IdempotencyGuard.REPLAY_HEADER)).isEqualTo("true");
    }

    @Test
    @DisplayName("the same key with a different amount is refused and changes no balance")
    void differentPayloadUnderTheSameKeyIsRefused() {
        String key = newKey();
        Ledger ledger = new Ledger(0);

        run(key, transfer("30.00"), () -> ledger.apply(new BigDecimal("30.00")));

        assertThatThrownBy(() -> run(key, transfer("500.00"), () -> ledger.apply(new BigDecimal("500.00"))))
                .isInstanceOf(IdempotencyException.class)
                .extracting(e -> ((IdempotencyException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(ledger.executions).hasValue(1);
        assertThat(ledger.sourceBalance).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("a refusal that moved nothing leaves the key usable again")
    void refusedRequestReleasesTheKey() {
        String key = newKey();
        TransferRequest request = transfer("30.00");
        Ledger ledger = new Ledger(0);

        assertThatThrownBy(() -> run(key, request, () -> {
            throw new TransactionException("Cannot transfer to the same account");
        })).isInstanceOf(TransactionException.class);

        assertThat(repository.findByIdempotencyKey(key))
                .get()
                .extracting(IdempotencyRecord::getStatus)
                .isEqualTo(IdempotencyStatus.FAILED);

        // Nothing was applied, so the client may correct course and retry under
        // the same key rather than being permanently locked out of it.
        ResponseEntity<TransferResponse> retry = run(key, request, () -> ledger.apply(request.getAmount()));

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ledger.executions).hasValue(1);
        assertThat(repository.findByIdempotencyKey(key))
                .get()
                .extracting(IdempotencyRecord::getStatus)
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("an attempt whose outcome is unknown is never re-executed under the same key")
    void unknownOutcomeIsNeverRetried() {
        String key = newKey();
        TransferRequest request = transfer("30.00");
        Ledger ledger = new Ledger(0);

        assertThatThrownBy(() -> run(key, request, () -> {
            // The call reached the account service; the answer never came back.
            // Whether the debit landed is not knowable from here.
            throw new AccountCallTimeoutException("The account service did not respond in time");
        })).isInstanceOf(AccountCallTimeoutException.class);

        assertThat(repository.findByIdempotencyKey(key))
                .get()
                .extracting(IdempotencyRecord::getStatus)
                .isEqualTo(IdempotencyStatus.UNKNOWN);

        assertThatThrownBy(() -> run(key, request, () -> ledger.apply(request.getAmount())))
                .isInstanceOf(IdempotencyException.class)
                .extracting(e -> ((IdempotencyException) e).getStatus())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

        // A retry here would be a coin-flip between a no-op and a second debit.
        assertThat(ledger.executions).hasValue(0);
        assertThat(ledger.sourceBalance).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("the database, not the application, enforces one row per key")
    void uniqueConstraintIsEnforcedByTheDatabase() {
        String key = newKey();
        repository.saveAndFlush(IdempotencyRecord.builder()
                .idempotencyKey(key).operation("TRANSFER").requestHash("a".repeat(64))
                .status(IdempotencyStatus.COMPLETED).build());

        // The check-then-insert in IdempotencyStore is an optimisation. This is
        // the part that actually makes a duplicate impossible.
        assertThatThrownBy(() -> repository.saveAndFlush(IdempotencyRecord.builder()
                .idempotencyKey(key).operation("TRANSFER").requestHash("b".repeat(64))
                .status(IdempotencyStatus.IN_PROGRESS).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the migrations apply to an empty database and match the entity mapping")
    void schemaMatchesTheEntity() {
        // ddl-auto: validate means the context would not have started if the
        // entity and the migration disagreed. Storing and reading a row proves
        // the columns behave as declared.
        String key = newKey();
        repository.saveAndFlush(IdempotencyRecord.builder()
                .idempotencyKey(key).operation("DEPOSIT").requestHash("c".repeat(64))
                .status(IdempotencyStatus.COMPLETED).responseStatus(201)
                .responseBody("{\"transactionRef\":\"abc\"}").resultRef("abc").build());

        Optional<IdempotencyRecord> stored = repository.findByIdempotencyKey(key);
        assertThat(stored).get().satisfies(record -> {
            assertThat(record.getCreatedAt()).isNotNull();
            assertThat(record.getUpdatedAt()).isNotNull();
            assertThat(record.getResponseBody()).isEqualTo("{\"transactionRef\":\"abc\"}");
        });
    }
}
