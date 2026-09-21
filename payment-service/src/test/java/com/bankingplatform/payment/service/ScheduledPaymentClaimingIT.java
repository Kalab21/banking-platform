package com.bankingplatform.payment.service;

import com.bankingplatform.payment.client.AccountClient;
import com.bankingplatform.payment.client.TransactionClient;
import com.bankingplatform.payment.kafka.producer.PaymentEventProducer;
import com.bankingplatform.payment.mapper.PaymentMapper;
import com.bankingplatform.payment.repository.AuditLogRepository;
import com.bankingplatform.payment.repository.PaymentRepository;
import com.bankingplatform.payment.service.impl.PaymentServiceImpl;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two payment workers must not process the same scheduled payment.
 *
 * <p>Against a real PostgreSQL, because the control is {@code FOR UPDATE SKIP
 * LOCKED}. Every replica runs the same 60-second timer over the same due
 * rows; without a claim they all process all of them. The downstream transfer
 * carries an idempotency key, so the money itself survives that — but the
 * recurrence does not. Two workers each schedule next month's occurrence, and
 * the customer is billed twice next month by two payments that are genuinely
 * different rows.
 *
 * <p>A mocked repository cannot show any of this. {@code SKIP LOCKED} is a
 * property of the database's lock manager.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// The workers must see each other's commits, so this must not run inside a
// transaction that rolls back at the end.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Scheduled payment claiming — PostgreSQL integration")
class ScheduledPaymentClaimingIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
            .withUsername("bankingadmin")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final int STALLED_AFTER_MINUTES = 15;

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private PaymentServiceImpl service;
    private TransactionTemplate inTransaction;

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(
                paymentRepository, auditLogRepository,
                Mockito.mock(PaymentMapper.class),
                Mockito.mock(PaymentEventProducer.class),
                Mockito.mock(TransactionClient.class),
                Mockito.mock(AccountClient.class));
        ReflectionTestUtils.setField(service, "stalledAfterMinutes", STALLED_AFTER_MINUTES);
        inTransaction = new TransactionTemplate(transactions);

        jdbc.update("DELETE FROM payments");
    }

    /** A payment row written directly, so its status and age are exact. */
    private long payment(String ref, String status, LocalDateTime scheduledAt,
                         LocalDateTime updatedAt) {
        jdbc.update("""
                INSERT INTO payments
                    (payment_ref, payer_account_id, payee_account_id, payment_type, amount,
                     currency, status, is_recurring, scheduled_at, created_at, updated_at)
                VALUES (?, 9, 10, 'INTERNAL', 25.00, 'USD', ?, false, ?, now(), ?)
                """, ref, status, Timestamp.valueOf(scheduledAt), Timestamp.valueOf(updatedAt));
        Long id = jdbc.queryForObject(
                "SELECT id FROM payments WHERE payment_ref = ?", Long.class, ref);
        return id == null ? 0 : id;
    }

    private long dueNow(String ref) {
        LocalDateTime now = LocalDateTime.now();
        return payment(ref, "PENDING", now.minusMinutes(1), now);
    }

    /**
     * One claim, in its own transaction.
     *
     * <p>The service is built by hand here, so its {@code @Transactional} is
     * not proxied; in production the proxy opens this transaction. It has to
     * be a real one either way — the lock the claim takes is transaction
     * scoped, and the two statements that make up a claim have to be in the
     * same one.
     */
    private List<Long> claim(int limit) {
        return inTransaction.execute(status -> service.claimScheduledPayments(limit));
    }

    private List<String> statuses() {
        return jdbc.queryForList("SELECT status FROM payments ORDER BY id", String.class);
    }

    @Test
    @DisplayName("two workers claiming at once never take the same payment")
    void twoWorkersPartitionTheWork() throws Exception {
        for (int i = 0; i < 20; i++) {
            dueNow("due-" + i);
        }

        int workers = 4;
        CyclicBarrier start = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Callable<List<Long>>> claims = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            claims.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                return claim(5);
            });
        }

        List<Future<List<Long>>> results = pool.invokeAll(claims);
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> result : results) {
            all.addAll(result.get());
        }
        Set<Long> distinct = new HashSet<>(all);

        assertThat(all).as("four workers, five each, none idle or blocked").hasSize(20);
        assertThat(distinct)
                .as("no payment claimed by two workers")
                .hasSize(all.size());
    }

    @Test
    @DisplayName("a claimed payment is not offered again")
    void aClaimIsDurable() {
        dueNow("due-1");

        List<Long> first = claim(10);
        List<Long> second = claim(10);

        assertThat(first).hasSize(1);
        assertThat(second)
                .as("the status change outlives the transaction that locked the row")
                .isEmpty();
        assertThat(statuses()).containsExactly("PROCESSING");
    }

    @Test
    @DisplayName("the batch size bounds what one worker takes")
    void theBatchIsBounded() {
        for (int i = 0; i < 10; i++) {
            dueNow("due-" + i);
        }

        // The previous version selected every due payment and worked through
        // them in one transaction, so a backlog became one enormous
        // transaction holding a lock on every row in it.
        assertThat(claim(3)).hasSize(3);
    }

    @Test
    @DisplayName("a payment left PROCESSING by a dead worker is re-claimed")
    void stalledPaymentsAreRecovered() {
        LocalDateTime now = LocalDateTime.now();
        payment("stalled", "PROCESSING", now.minusHours(2),
                now.minusMinutes(STALLED_AFTER_MINUTES + 5L));

        // Without this the claim is a one-way door: a worker that dies
        // between claiming and processing strands the payment in PROCESSING
        // and nothing ever looks at it again.
        assertThat(claim(10)).hasSize(1);
    }

    @Test
    @DisplayName("a payment still being worked on is left alone")
    void freshlyClaimedPaymentsAreNotStolen() {
        LocalDateTime now = LocalDateTime.now();
        payment("in-flight", "PROCESSING", now.minusHours(2),
                now.minusMinutes(STALLED_AFTER_MINUTES - 5L));

        // The grace period is what stops recovery from becoming the very
        // duplicate processing the claim exists to prevent.
        assertThat(claim(10)).isEmpty();
    }

    @Test
    @DisplayName("a payment that is not yet due is not claimed")
    void futurePaymentsWait() {
        LocalDateTime now = LocalDateTime.now();
        payment("later", "PENDING", now.plusHours(1), now);

        assertThat(claim(10)).isEmpty();
        assertThat(statuses()).containsExactly("PENDING");
    }
}
