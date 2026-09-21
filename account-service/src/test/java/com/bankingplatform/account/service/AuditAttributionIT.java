package com.bankingplatform.account.service;

import com.bankingplatform.account.client.UserClient;
import com.bankingplatform.account.dto.BalanceUpdateRequest;
import com.bankingplatform.account.kafka.producer.AccountEventProducer;
import com.bankingplatform.account.mapper.AccountMapperImpl;
import com.bankingplatform.account.repository.AccountRepository;
import com.bankingplatform.account.repository.AuditLogRepository;
import com.bankingplatform.account.service.impl.AccountServiceImpl;
import com.bankingplatform.common.security.CallerContext;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An audit row has to say who did it.
 *
 * <p>{@code performed_by} was nullable and null at almost every call site, so
 * the log recorded what happened and not who was responsible — which is most
 * of the reason to keep one. Where it was filled, several call sites passed
 * the <em>subject</em> of the change rather than the actor, so an employee
 * acting on a customer's account produced a row naming the customer.
 *
 * <p>Against real PostgreSQL, because the column and its default are part of
 * the claim: an existing row backfilled as {@code UNKNOWN} must stay
 * distinguishable from a new one written by the platform itself.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Audit attribution — PostgreSQL integration")
class AuditAttributionIT {

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

    @Autowired private AccountRepository accountRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private AccountServiceImpl accounts;
    private TransactionTemplate inTransaction;
    private Long accountId;

    @BeforeEach
    void setUp() {
        accounts = new AccountServiceImpl(
                accountRepository, auditLogRepository, new AccountMapperImpl(),
                Mockito.mock(AccountEventProducer.class),
                Mockito.mock(UserClient.class));
        inTransaction = new TransactionTemplate(transactions);

        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("""
                INSERT INTO accounts (account_number, user_id, account_type, status, balance,
                                      currency, interest_rate, overdraft_limit, overdraft_balance,
                                      created_at, updated_at)
                VALUES ('AUD-1', 42, 'CHECKING', 'ACTIVE', 500.00, 'USD', 0.0000, 0.00, 0.00,
                        now(), now())
                """);
        accountId = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE account_number = 'AUD-1'", Long.class);
    }

    private void debit() {
        BalanceUpdateRequest request = new BalanceUpdateRequest();
        request.setAmount(new BigDecimal("10.00"));
        request.setOperation("DEBIT");
        inTransaction.executeWithoutResult(status -> accounts.updateBalance(accountId, request));
    }

    private Map<String, Object> lastAudit() {
        return jdbc.queryForMap(
                "SELECT actor_type, performed_by, action FROM audit_log ORDER BY id DESC LIMIT 1");
    }

    @Test
    @DisplayName("a customer's own action names the customer")
    void customerActionIsAttributed() {
        CallerContext.runAs(new CallerIdentity(42L, "customer42", Role.CUSTOMER), this::debit);

        assertThat(lastAudit())
                .containsEntry("actor_type", "CUSTOMER")
                .containsEntry("performed_by", 42L);
    }

    @Test
    @DisplayName("staff acting on a customer's account name the member of staff")
    void staffActionIsNotAttributedToTheCustomer() {
        // The account belongs to user 42. The actor is employee 7, and the
        // row has to say so — the previous version would have recorded the
        // account holder, or nothing at all.
        CallerContext.runAs(new CallerIdentity(7L, "reviewer", Role.EMPLOYEE), this::debit);

        assertThat(lastAudit())
                .containsEntry("actor_type", "EMPLOYEE")
                .containsEntry("performed_by", 7L);
    }

    @Test
    @DisplayName("the platform acting on its own behalf says so, rather than leaving a null")
    void systemActionIsAStatement() {
        // No caller: a Kafka listener or a scheduled job. A null performed_by
        // beside SYSTEM means "no user was involved". A null beside nothing
        // used to mean either that or "the attribution was lost".
        debit();

        assertThat(lastAudit())
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("performed_by", null);
    }

    @Test
    @DisplayName("rows written before this existed are UNKNOWN, not silently claimed as system")
    void backfilledRowsAreHonest() {
        // The migration defaults existing rows to UNKNOWN rather than
        // inventing an actor for them. Claiming they were system actions
        // would be worse than admitting the platform does not know.
        jdbc.update("""
                INSERT INTO audit_log (entity_type, entity_id, action, performed_by, details, created_at)
                VALUES ('ACCOUNT', ?, 'LEGACY', NULL, 'written before attribution existed', now())
                """, accountId);

        assertThat(jdbc.queryForObject(
                "SELECT actor_type FROM audit_log WHERE action = 'LEGACY'", String.class))
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("every audit row has an actor type, so the column can be relied on")
    void actorTypeIsNeverNull() {
        CallerContext.runAs(new CallerIdentity(42L, "customer42", Role.CUSTOMER), this::debit);
        debit();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_type IS NULL", Long.class)).isZero();
    }
}
