package com.bankingplatform.loan.kafka.consumer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.kafka.inbox.JdbcProcessedEventGuard;
import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.service.LoanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
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
                new BigDecimal("10000"));
    }

    private long claimsFor(String eventId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE event_id = ?", Long.class, eventId);
        return count == null ? 0 : count;
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
    @DisplayName("a different event for the same application is a different event")
    void distinctEventsAreNotCollapsed() {
        // Two publications of the same business fact are still two events.
        // De-duplication keys on the publication, not on the application, so
        // a genuine re-approval is not silently swallowed — the unique index
        // on application_id is what stops a second loan existing.
        deliver(approval(7002L));
        deliver(approval(7002L));

        verify(loanService, times(2)).createLoan(any(CreateLoanRequest.class));
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

        int failures = 0;
        for (Future<Void> result : results) {
            try {
                result.get();
            } catch (Exception losingRace) {
                // A loser may surface as a constraint violation rather than a
                // quiet no-op depending on how the race resolves. Either is a
                // correct outcome; issuing a second loan is not.
                failures++;
            }
        }

        verify(loanService, times(1)).createLoan(any(CreateLoanRequest.class));
        assertThat(claimsFor(approved.eventId())).isEqualTo(1);
        assertThat(failures)
                .as("seven of the eight lose the race, one way or another")
                .isLessThanOrEqualTo(threads - 1);
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

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM loans WHERE application_id IS NULL", Long.class);
        assertThat(count).isEqualTo(2);
    }
}
