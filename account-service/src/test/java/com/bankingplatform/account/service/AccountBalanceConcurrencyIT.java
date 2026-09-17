package com.bankingplatform.account.service;

import com.bankingplatform.account.client.UserClient;
import com.bankingplatform.account.dto.BalanceUpdateRequest;
import com.bankingplatform.account.exception.InsufficientFundsException;
import com.bankingplatform.account.kafka.producer.AccountEventProducer;
import com.bankingplatform.account.mapper.AccountMapperImpl;
import com.bankingplatform.account.model.Account;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.model.AccountType;
import com.bankingplatform.account.repository.AccountRepository;
import com.bankingplatform.account.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent balance movement against a real PostgreSQL database.
 *
 * <p>The defect these exist for: {@code updateBalance} reads the balance,
 * decides whether it is sufficient, and writes a new figure. Run twice at once
 * without a row lock, both reads see the same starting balance, both checks
 * pass, and the second write overwrites the first — an account holding 100 pays
 * out 80 twice and ends at 20 instead of refusing the second debit.
 *
 * <p>That is a property of the database's locking, so it can only be shown
 * against a database. A mocked repository would demonstrate the mock's
 * behaviour, not PostgreSQL's, and would pass just as happily with the lock
 * removed.
 *
 * <p>The tests therefore run outside a test-managed transaction: each thread
 * needs its own transaction that really commits, which a rollback-only wrapper
 * would not give.
 *
 * <p>Named {@code *IT} and bound to Failsafe, so {@code mvn test} skips it and
 * {@code mvn verify} runs it. Requires a working Docker daemon.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AccountServiceImpl.class, AccountMapperImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Concurrent balance movement — PostgreSQL integration")
class AccountBalanceConcurrencyIT {

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("account_db")
                    .withUsername("bankingadmin")
                    .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Kafka and the user service are not what these tests are about. */
    @MockBean private AccountEventProducer eventProducer;
    @MockBean private UserClient userClient;

    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;

    // ------------------------------------------------------------- fixtures

    private Account givenAccount(String balance, String overdraftLimit) {
        return accountRepository.saveAndFlush(Account.builder()
                .accountNumber("BA" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .userId(7L)
                .accountType(AccountType.CHECKING)
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal(balance))
                .currency("USD")
                .interestRate(BigDecimal.ZERO)
                .overdraftLimit(new BigDecimal(overdraftLimit))
                .overdraftBalance(BigDecimal.ZERO)
                .build());
    }

    private static BalanceUpdateRequest debit(String amount) {
        BalanceUpdateRequest request = new BalanceUpdateRequest();
        request.setOperation("DEBIT");
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    /**
     * Runs every debit at once and reports which ones the service accepted.
     *
     * <p>The barrier matters: without it the first request would usually finish
     * before the second started, and the test would pass whether or not the row
     * is locked.
     */
    private List<Outcome> debitTogether(Long accountId, String... amounts) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(amounts.length);
        ExecutorService pool = Executors.newFixedThreadPool(amounts.length);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (String amount : amounts) {
                Callable<Outcome> attempt = () -> {
                    startTogether.await(30, TimeUnit.SECONDS);
                    try {
                        accountService.updateBalance(accountId, debit(amount));
                        return new Outcome(amount, true, null);
                    } catch (RuntimeException refused) {
                        return new Outcome(amount, false, refused);
                    }
                };
                futures.add(pool.submit(attempt));
            }

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Outcome(String amount, boolean accepted, RuntimeException refusal) {
    }

    private static long accepted(List<Outcome> outcomes) {
        return outcomes.stream().filter(Outcome::accepted).count();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("two simultaneous debits of 80 against a balance of 100: one succeeds, the balance never goes negative")
    void concurrentDebitsCannotBothWin() throws Exception {
        Account account = givenAccount("100.00", "0.00");

        List<Outcome> outcomes = debitTogether(account.getId(), "80.00", "80.00");

        assertThat(accepted(outcomes)).isEqualTo(1);
        outcomes.stream().filter(outcome -> !outcome.accepted()).forEach(outcome ->
                assertThat(outcome.refusal()).isInstanceOf(InsufficientFundsException.class));

        // 20.00, and specifically not -60.00. The account has no overdraft, so
        // there is no arrangement under which paying out 160 is permitted.
        assertThat(balanceOf(account.getId())).isEqualByComparingTo("20.00");
        assertThat(balanceOf(account.getId())).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("two simultaneous debits that both fit are both applied, with neither update lost")
    void concurrentDebitsThatBothFitAreBothApplied() throws Exception {
        Account account = givenAccount("100.00", "0.00");

        List<Outcome> outcomes = debitTogether(account.getId(), "30.00", "20.00");

        assertThat(accepted(outcomes)).isEqualTo(2);

        // 50.00. A lost update would leave 70.00 or 80.00 — one debit applied
        // to a balance the other had already changed.
        assertThat(balanceOf(account.getId())).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("overdraft head-room is measured against the balance the previous debit left")
    void overdraftHeadroomIsEvaluatedAgainstTheLockedBalance() throws Exception {
        // Balance 100, overdraft limit 50, so 150 is available in total.
        Account account = givenAccount("100.00", "50.00");

        List<Outcome> outcomes = debitTogether(account.getId(), "80.00", "80.00");

        // The first debit leaves 20.00 with the whole 50.00 of head-room still
        // unused, so 70.00 is available — not enough for the second 80.00. The
        // check has to run against the post-first balance to reach that answer.
        assertThat(accepted(outcomes)).isEqualTo(1);
        assertThat(balanceOf(account.getId())).isEqualByComparingTo("20.00");

        Account after = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getOverdraftBalance()).isEqualByComparingTo("0.00");
        assertThat(after.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("many simultaneous debits drain the balance exactly, never past it")
    void manyConcurrentDebitsStopAtZero() throws Exception {
        Account account = givenAccount("50.00", "0.00");

        String[] tenDebits = new String[10];
        java.util.Arrays.fill(tenDebits, "10.00");
        List<Outcome> outcomes = debitTogether(account.getId(), tenDebits);

        // Exactly five of the ten can be afforded. Under a lost update the
        // count comes out higher and the balance comes out negative.
        assertThat(accepted(outcomes)).isEqualTo(5);
        assertThat(balanceOf(account.getId())).isEqualByComparingTo("0.00");
    }
}
