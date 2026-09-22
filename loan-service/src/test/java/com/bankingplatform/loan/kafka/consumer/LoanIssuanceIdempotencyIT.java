package com.bankingplatform.loan.kafka.consumer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.kafka.inbox.JdbcProcessedEventGuard;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.service.LoanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * One approved application issues one loan, however many times it is delivered.
 *
 * <p>Against a real PostgreSQL, because the control is a unique constraint.
 * A mock would assert the guard was called; only the database can show that
 * two concurrent claims resolve to one winner.
 *
 * <p>This is the consumer that matters most. Kafka is at-least-once and this
 * service retries, so a redelivery is ordinary — and the side effect here is
 * lending someone money.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LoanIssuanceIdempotencyIT.GuardConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Loan issuance idempotency — PostgreSQL integration")
class LoanIssuanceIdempotencyIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("loan_db")
            .withUsername("bankingadmin")
            .withPassword("bankingpass");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    /**
     * The container-managed guard, so its {@code @Transactional(MANDATORY)}
     * is actually applied. The hand-built one used elsewhere in this class is
     * a plain object and carries no proxy.
     */
    @Autowired
    private ProcessedEventGuard springManagedGuard;

    private final LoanService loanService = Mockito.mock(LoanService.class);

    private ApplicationEventConsumer consumer;
    private TransactionTemplate inTransaction;

    @BeforeEach
    void setUp() {
        // Built by hand rather than imported: the consumer carries
        // @KafkaListener, and importing it into a JPA slice asks for Kafka
        // infrastructure this test has no use for.
        consumer = new ApplicationEventConsumer(loanService, new JdbcProcessedEventGuard(jdbc));
        inTransaction = new TransactionTemplate(transactions);
    }

    /**
     * One delivery, in a real transaction.
     *
     * <p>In production the listener's own {@code @Transactional} provides it.
     * The guard demands a surrounding transaction — it uses MANDATORY
     * propagation precisely so the claim cannot commit separately from the
     * work — so the test has to supply one too.
     */
    private void deliver(ApplicationApproved approved) {
        inTransaction.executeWithoutResult(status -> consumer.onApplicationEvent(approved));
    }

    private static ApplicationApproved approval(Long applicationId) {
        return ApplicationApproved.of(applicationId, 42L, "PERSONAL_LOAN", null, 720,
                new BigDecimal("10000"), new BigDecimal("10000"));
    }

    private long claimsFor(String eventId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE event_id = ?", Long.class, eventId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("the guard refuses to claim outside a transaction")
    void claimRequiresATransaction() {
        // The hole MANDATORY closes: a consumer whose listener forgot
        // @Transactional would auto-commit the claim, fail the work, and mark
        // the event processed for good with nothing left to retry it. The
        // container-managed guard must refuse rather than oblige.
        assertThatThrownBy(() -> springManagedGuard.claim("no-transaction", "evt-x"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("the same event delivered twice issues one loan")
    void redeliveryIssuesOneLoan() {
        ApplicationApproved approved = approval(7001L);

        deliver(approved);
        deliver(approved);

        verify(loanService, times(1)).createLoan(any(CreateLoanRequest.class));
        assertThat(claimsFor(approved.eventId()))
                .as("one processed-event row, not two")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a re-approval reaches the service and is absorbed by the unique index")
    void reApprovalIsNotAPoisonRecord() {
        // Two publications of the same business fact are two events: the claim
        // keys on the publication, so the second one passes the guard and
        // reaches createLoan. The index is what stops a second loan existing,
        // and the consumer treats that refusal as "already issued" rather than
        // letting it retry four times and dead-letter a normal business case.
        Mockito.doThrow(new DuplicateKeyException("ux_loans_application_id"))
                .when(loanService).createLoan(any(CreateLoanRequest.class));

        deliver(approval(7002L));

        verify(loanService, times(1)).createLoan(any(CreateLoanRequest.class));
    }

    @Test
    @DisplayName("concurrent duplicate deliveries issue one loan")
    void concurrentDuplicatesIssueOneLoan() throws Exception {
        ApplicationApproved approved = approval(7003L);

        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            attempts.add(() -> {
                // Released together, so the claims genuinely race rather than
                // running one after another.
                start.await(20, TimeUnit.SECONDS);
                deliver(approved);
                return null;
            });
        }

        List<Future<Void>> results = pool.invokeAll(attempts);
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        for (Future<Void> result : results) {
            // Every delivery resolves cleanly: the losers see ON CONFLICT DO
            // NOTHING and return, rather than throwing. A raised exception
            // here would mean a redelivery could poison a partition.
            result.get();
        }

        verify(loanService, times(1)).createLoan(any(CreateLoanRequest.class));
        assertThat(claimsFor(approved.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the database refuses a second loan for one application, guard or no guard")
    void applicationIdIsUniqueInTheDatabase() {
        jdbc.update("""
                INSERT INTO loans (user_id, application_id, loan_type, principal, interest_rate,
                                   term_months, monthly_payment, total_interest,
                                   remaining_balance, status, currency, created_at)
                VALUES (42, 9100, 'PERSONAL_LOAN', 10000, 10.99, 48, 258.00, 2384.00, 10000,
                        'PENDING_DISBURSEMENT', 'USD', now())
                """);

        // Defence in depth: this is the state the platform must not be able to
        // represent, whatever route a duplicate arrives by — a replay from the
        // dead letter topic, or a future consumer that forgets the guard.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO loans (user_id, application_id, loan_type, principal, interest_rate,
                                   term_months, monthly_payment, total_interest,
                                   remaining_balance, status, currency, created_at)
                VALUES (42, 9100, 'PERSONAL_LOAN', 10000, 10.99, 48, 258.00, 2384.00, 10000,
                        'PENDING_DISBURSEMENT', 'USD', now())
                """))
                .as("a second loan for application 9100")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("loans created outside an application are unaffected by that index")
    void nullApplicationIdStaysAllowed() {
        // The index is partial for this reason: a loan with no originating
        // application has no id to be unique on, and there may be many.
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                    INSERT INTO loans (user_id, application_id, loan_type, principal, interest_rate,
                                       term_months, monthly_payment, total_interest,
                                       remaining_balance, status, currency, created_at)
                    VALUES (42, NULL, 'PERSONAL_LOAN', 5000, 10.99, 24, 230.00, 520.00, 5000,
                            'PENDING_DISBURSEMENT', 'USD', now())
                    """);
        }

        // Scoped to the rows this test inserts. An absolute count over every
        // application-less loan would break the moment another test in this
        // class created one, and the container is shared across the class.
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM loans WHERE application_id IS NULL AND principal = 5000",
                Long.class);
        assertThat(count).isEqualTo(2);
    }

    /**
     * The guard as the container builds it, so its {@code @Transactional}
     * is actually proxied. A hand-constructed instance carries no proxy, and
     * a test using one would never exercise the MANDATORY propagation the
     * class relies on.
     */
    @TestConfiguration
    static class GuardConfig {

        @Bean
        ProcessedEventGuard springManagedGuard(JdbcTemplate jdbc) {
            return new JdbcProcessedEventGuard(jdbc);
        }
    }
}
