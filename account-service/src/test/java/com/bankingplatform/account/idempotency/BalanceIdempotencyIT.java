package com.bankingplatform.account.idempotency;

import com.bankingplatform.account.client.UserClient;
import com.bankingplatform.account.dto.AccountResponse;
import com.bankingplatform.account.dto.BalanceUpdateRequest;
import com.bankingplatform.account.exception.InsufficientFundsException;
import com.bankingplatform.account.kafka.producer.AccountEventProducer;
import com.bankingplatform.account.mapper.AccountMapperImpl;
import com.bankingplatform.account.repository.AccountRepository;
import com.bankingplatform.account.repository.AuditLogRepository;
import com.bankingplatform.account.service.impl.AccountServiceImpl;
import com.bankingplatform.common.idempotency.IdempotencyException;
import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyOutcome;
import com.bankingplatform.common.idempotency.IdempotencyStatus;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A balance movement is applied at most once per key.
 *
 * <p>This is the one place on the platform where a balance actually changes.
 * Every service that moves money — transfer, card payment, cash advance, loan
 * repayment and disbursement — arrives here over HTTP, and a call that times
 * out tells the caller nothing about whether it applied. Without a key the
 * caller has only bad options: never retry, and strand a movement that may
 * not have happened; or retry, and debit twice.
 *
 * <p>Against real PostgreSQL, because the control is a unique constraint: two
 * requests carrying one key both attempt the insert and the database picks
 * the winner.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// The guard commits its claim before the movement runs, so a test-managed
// transaction that rolled back at the end would hide those commits.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Balance idempotency — PostgreSQL integration")
class BalanceIdempotencyIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("account_db")
            .withUsername("bankingadmin")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String BALANCE = "BALANCE";
    private static final BigDecimal OPENING = new BigDecimal("500.00");

    @Autowired private AccountRepository accountRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private AccountServiceImpl accounts;
    private IdempotencyStore store;
    private IdempotencyGuard guard;
    private TransactionTemplate inTransaction;
    private Long accountId;
    private Long otherAccountId;

    @BeforeEach
    void setUp() {
        accounts = new AccountServiceImpl(
                // The generated MapStruct implementation rather than a bean:
                // a JPA slice has no mapper in its context, and mocking it
                // would hide the response the replay is read back from.
                accountRepository, auditLogRepository, new AccountMapperImpl(),
                Mockito.mock(AccountEventProducer.class),
                Mockito.mock(UserClient.class));
        store = new IdempotencyStore(jdbc);
        guard = new IdempotencyGuard(store, new ObjectMapper().registerModule(new JavaTimeModule()),
                new BalanceOutcomeClassifier());
        inTransaction = new TransactionTemplate(transactions);

        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM accounts");

        accountId = openAccount("ACC-1");
        otherAccountId = openAccount("ACC-2");
    }

    private Long openAccount(String number) {
        jdbc.update("""
                INSERT INTO accounts (account_number, user_id, account_type, status, balance,
                                      currency, interest_rate, overdraft_limit, overdraft_balance,
                                      created_at, updated_at)
                VALUES (?, 42, 'CHECKING', 'ACTIVE', ?, 'USD', 0.0000, 0.00, 0.00, now(), now())
                """, number, OPENING);
        return jdbc.queryForObject(
                "SELECT id FROM accounts WHERE account_number = ?", Long.class, number);
    }

    private static String newKey() {
        return "txn-" + UUID.randomUUID();
    }

    private static BalanceUpdateRequest move(String operation, String amount) {
        BalanceUpdateRequest request = new BalanceUpdateRequest();
        request.setAmount(new BigDecimal(amount));
        request.setOperation(operation);
        return request;
    }

    /** Exactly what the internal controller does, including the status. */
    private ResponseEntity<AccountResponse> apply(String key, Long id, BalanceUpdateRequest request) {
        return guard.execute(key, BALANCE, null, Map.of("accountId", id, "request", request),
                AccountResponse.class, response -> String.valueOf(response.getId()),
                HttpStatus.OK,
                () -> inTransaction.execute(status -> accounts.updateBalance(id, request)));
    }

    private BigDecimal balanceOf(Long id) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id);
    }

    @Test
    @DisplayName("the same debit retried under one key is applied once")
    void retriedDebitAppliesOnce() {
        String key = newKey();

        ResponseEntity<AccountResponse> first = apply(key, accountId, move("DEBIT", "100.00"));
        ResponseEntity<AccountResponse> replay = apply(key, accountId, move("DEBIT", "100.00"));

        assertThat(balanceOf(accountId)).isEqualByComparingTo("400.00");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst(IdempotencyGuard.REPLAY_HEADER))
                .as("the caller is told this was served rather than applied")
                .isEqualTo("true");
        assertThat(replay.getBody()).isNotNull();
    }

    @Test
    @DisplayName("a replay answers 200, not 201: a balance movement creates nothing")
    void replayCarriesTheOperationsOwnStatus() {
        String key = newKey();
        apply(key, accountId, move("CREDIT", "50.00"));

        assertThat(apply(key, accountId, move("CREDIT", "50.00")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("concurrent duplicates move the balance once")
    void concurrentDuplicatesApplyOnce() throws Exception {
        String key = newKey();
        int callers = 8;
        CyclicBarrier start = new CyclicBarrier(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);

        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            attempts.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                try {
                    apply(key, accountId, move("DEBIT", "100.00"));
                } catch (IdempotencyException stillRunning) {
                    // A duplicate that waited out its budget. It applied
                    // nothing, which is the guarantee under test.
                }
                return null;
            });
        }
        pool.invokeAll(attempts);
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        assertThat(balanceOf(accountId))
                .as("eight callers, one debit")
                .isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("one key against two accounts is refused, not merged")
    void theAccountIsPartOfTheFingerprint() {
        // The account is a path variable rather than a body field. A
        // fingerprint over the body alone would make these look like one
        // request, and the second account would be served the first one's
        // response and never move.
        String key = newKey();
        apply(key, accountId, move("DEBIT", "100.00"));

        assertThatThrownBy(() -> apply(key, otherAccountId, move("DEBIT", "100.00")))
                .isInstanceOf(IdempotencyException.class);
        assertThat(balanceOf(otherAccountId)).isEqualByComparingTo(OPENING);
    }

    @Test
    @DisplayName("a debit refused for want of funds releases the key")
    void refusalReleasesTheKey() {
        // This service owns the balance, so a refusal here is an answer, not
        // an unknown: the transaction rolled back and nothing moved. The
        // caller may credit the account and retry under the same key, which a
        // cached refusal would prevent.
        String key = newKey();

        assertThatThrownBy(() -> apply(key, accountId, move("DEBIT", "9000.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(store.find(key)).get()
                .extracting(IdempotencyOutcome::status)
                .isEqualTo(IdempotencyStatus.FAILED);

        // The retry runs the operation again rather than being told the key is
        // spent. It fails on the funds, not on idempotency -- which is the
        // distinction: an InsufficientFundsException here means the key was
        // released, an IdempotencyException would mean it was not.
        assertThatThrownBy(() -> apply(key, accountId, move("DEBIT", "9000.00")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(balanceOf(accountId))
                .as("still refused, and still nothing moved")
                .isEqualByComparingTo(OPENING);
    }

    @Test
    @DisplayName("two genuinely different movements both apply")
    void distinctKeysBothApply() {
        // The key names a movement, not a request shape. Two identical debits
        // that are genuinely two debits must both land.
        apply(newKey(), accountId, move("DEBIT", "100.00"));
        apply(newKey(), accountId, move("DEBIT", "100.00"));

        assertThat(balanceOf(accountId)).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("the account service's own migration holds what the shared store writes")
    void schemaHoldsWhatTheStoreWrites() {
        // Flyway applies account-service's V3 to an empty database here, so
        // the shared store and this service's table cannot drift apart
        // without this failing.
        String key = newKey();
        assertThat(store.claim(key, BALANCE, "e".repeat(64))).isEmpty();
        store.complete(key, 200, "{\"id\":1}", "1");

        assertThat(store.find(key)).get().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BALANCE);
            assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(record.responseStatus()).isEqualTo(200);
        });
    }
}
