package com.bankingplatform.account.repository;

import com.bankingplatform.account.model.Account;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.model.AccountType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration slice for the account persistence layer, run against a real PostgreSQL
 * container rather than an in-memory substitute.
 *
 * <p>This covers three things a mocked repository cannot:
 * <ul>
 *   <li>the Flyway migrations apply cleanly to an empty PostgreSQL database;</li>
 *   <li>the JPA mappings agree with the migrated schema — the service runs
 *       {@code ddl-auto: validate}, so a drift between entity and migration fails
 *       this test at context startup;</li>
 *   <li>money survives a database round trip at {@code DECIMAL(19,2)} without
 *       losing scale, and the schema's own constraints are enforced.</li>
 * </ul>
 *
 * <p>Named {@code *IT} and bound to Failsafe, so it runs under {@code mvn verify}
 * and is skipped by a bare {@code mvn test}. Requires a working Docker daemon.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Account persistence — PostgreSQL integration")
class AccountRepositoryIT {

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

    @Autowired private AccountRepository accountRepository;
    @Autowired private EntityManager entityManager;

    private static Account account(String accountNumber, long userId, AccountStatus status, String balance) {
        return Account.builder()
                .accountNumber(accountNumber)
                .userId(userId)
                .accountType(AccountType.CHECKING)
                .status(status)
                .balance(new BigDecimal(balance))
                .currency("USD")
                .interestRate(new BigDecimal("0.0125"))
                .overdraftLimit(new BigDecimal("500.00"))
                .overdraftBalance(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("Flyway applies the migrations and creates the accounts table")
    void flywayMigrationsApplied() {
        Number migrations = (Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")
                .getSingleResult();
        assertThat(migrations.intValue()).isPositive();

        Number tables = (Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN ('accounts', 'audit_log')")
                .getSingleResult();
        assertThat(tables.intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("an account round-trips through PostgreSQL with its monetary scale intact")
    void persistsAndReadsBackAnAccount() {
        Account saved = accountRepository.saveAndFlush(account("BA250101000001", 1L, AccountStatus.ACTIVE, "1234.56"));
        entityManager.clear();

        Optional<Account> found = accountRepository.findById(saved.getId());

        assertThat(found).isPresent();
        Account result = found.orElseThrow();
        assertThat(result.getAccountNumber()).isEqualTo("BA250101000001");
        assertThat(result.getAccountType()).isEqualTo(AccountType.CHECKING);
        assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        // DECIMAL(19,2) must come back as 1234.56 exactly, not 1234.5599999
        assertThat(result.getBalance()).isEqualByComparingTo("1234.56");
        assertThat(result.getOverdraftLimit()).isEqualByComparingTo("500.00");
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("a debit committed against PostgreSQL is visible on re-read")
    void balanceChangeIsPersisted() {
        Account saved = accountRepository.saveAndFlush(account("BA250101000002", 2L, AccountStatus.ACTIVE, "500.00"));

        saved.setBalance(saved.getBalance().subtract(new BigDecimal("120.50")));
        accountRepository.saveAndFlush(saved);
        entityManager.clear();

        Account reloaded = accountRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("379.50");
    }

    @Test
    @DisplayName("an overdrawn account persists a negative balance and its overdraft position")
    void persistsOverdrawnPosition() {
        Account overdrawn = account("BA250101000003", 3L, AccountStatus.OVERDRAWN, "-35.00");
        overdrawn.setOverdraftBalance(new BigDecimal("300.00"));

        Account saved = accountRepository.saveAndFlush(overdrawn);
        entityManager.clear();

        Account reloaded = accountRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("-35.00");
        assertThat(reloaded.getOverdraftBalance()).isEqualByComparingTo("300.00");
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.OVERDRAWN);
    }

    @Test
    @DisplayName("the schema refuses a duplicate account number")
    void accountNumberIsUnique() {
        accountRepository.saveAndFlush(account("BA250101000004", 4L, AccountStatus.ACTIVE, "10.00"));

        assertThatThrownBy(() ->
                accountRepository.saveAndFlush(account("BA250101000004", 5L, AccountStatus.ACTIVE, "20.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("lookups by owner and by status are answered from the database")
    void queriesByOwnerAndStatus() {
        accountRepository.saveAndFlush(account("BA250101000005", 6L, AccountStatus.ACTIVE, "10.00"));
        accountRepository.saveAndFlush(account("BA250101000006", 6L, AccountStatus.FROZEN, "20.00"));
        accountRepository.saveAndFlush(account("BA250101000007", 7L, AccountStatus.ACTIVE, "30.00"));
        entityManager.clear();

        List<Account> ownersAccounts = accountRepository.findByUserId(6L);
        assertThat(ownersAccounts).hasSize(2);

        List<Account> activeOnly = accountRepository.findByUserIdAndStatus(6L, AccountStatus.ACTIVE);
        assertThat(activeOnly).hasSize(1);
        assertThat(activeOnly.get(0).getAccountNumber()).isEqualTo("BA250101000005");

        assertThat(accountRepository.existsByAccountNumber("BA250101000005")).isTrue();
        assertThat(accountRepository.existsByAccountNumber("BA999999999999")).isFalse();

        assertThat(accountRepository.findByAccountNumber("BA250101000007"))
                .isPresent()
                .get()
                .extracting(Account::getUserId)
                .isEqualTo(7L);
    }
}
