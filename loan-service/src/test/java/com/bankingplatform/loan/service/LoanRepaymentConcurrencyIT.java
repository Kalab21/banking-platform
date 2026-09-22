package com.bankingplatform.loan.service;

import com.bankingplatform.loan.client.AccountClient;
import com.bankingplatform.loan.dto.request.LoanRepaymentRequest;
import com.bankingplatform.loan.kafka.producer.LoanEventProducer;
import com.bankingplatform.loan.mapper.AmortizationMapper;
import com.bankingplatform.loan.mapper.LoanMapper;
import com.bankingplatform.loan.mapper.LoanRepaymentMapper;
import com.bankingplatform.loan.model.AmortizationSchedule;
import com.bankingplatform.loan.model.Loan;
import com.bankingplatform.loan.model.LoanStatus;
import com.bankingplatform.loan.model.LoanType;
import com.bankingplatform.loan.model.ScheduleStatus;
import com.bankingplatform.loan.repository.AmortizationScheduleRepository;
import com.bankingplatform.loan.repository.LoanRepaymentRepository;
import com.bankingplatform.loan.repository.LoanRepository;
import com.bankingplatform.loan.service.impl.LoanServiceImpl;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two repayments arriving together must not settle one instalment twice.
 *
 * <p>Against a real PostgreSQL, because the control is a row lock. A
 * repayment reads the loan, picks the first unpaid instalment, computes a new
 * remaining balance from what it read, and writes all of it back. Unlocked,
 * two concurrent repayments read the same balance and pick the same
 * instalment: the customer is debited twice, one instalment absorbs both, and
 * the loan is reduced by a single payment.
 *
 * <p>A mocked repository cannot show that. It would show that {@code save}
 * was called.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// The threads have to see each other's commits, so this must not run inside a
// transaction that rolls back at the end.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Loan repayment concurrency — PostgreSQL integration")
class LoanRepaymentConcurrencyIT {

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

    private static final int THREADS = 8;
    private static final BigDecimal PRINCIPAL = new BigDecimal("12000.00");
    private static final BigDecimal INSTALMENT = new BigDecimal("1000.00");

    @Autowired private LoanRepository loanRepository;
    @Autowired private AmortizationScheduleRepository scheduleRepository;
    @Autowired private LoanRepaymentRepository repaymentRepository;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private LoanServiceImpl service;
    private TransactionTemplate inTransaction;
    private Long loanId;

    @BeforeEach
    void setUp() {
        service = new LoanServiceImpl(
                loanRepository, scheduleRepository, repaymentRepository,
                Mockito.mock(AccountClient.class),
                Mockito.mock(AccountOwnershipGuard.class),
                Mockito.mock(LoanEventProducer.class),
                Mockito.mock(LoanMapper.class),
                Mockito.mock(AmortizationMapper.class),
                Mockito.mock(LoanRepaymentMapper.class));
        inTransaction = new TransactionTemplate(transactions);

        jdbc.update("DELETE FROM loan_repayments");
        jdbc.update("DELETE FROM amortization_schedules");
        jdbc.update("DELETE FROM loans");

        loanId = inTransaction.execute(status -> {
            Loan loan = loanRepository.save(Loan.builder()
                    .userId(42L)
                    .loanType(LoanType.PERSONAL_LOAN)
                    .principal(PRINCIPAL)
                    .interestRate(new BigDecimal("12.00"))
                    .termMonths(12)
                    .monthlyPayment(INSTALMENT)
                    .totalInterest(new BigDecimal("0.00"))
                    .remainingBalance(PRINCIPAL)
                    .paymentsMade(0)
                    .nextPaymentDate(LocalDate.now().plusDays(30))
                    .status(LoanStatus.ACTIVE)
                    .currency("USD")
                    .build());

            // Twelve instalments, inserted last-to-first, so physical row
            // order is the reverse of payment order.
            for (int number = 12; number >= 1; number--) {
                scheduleRepository.save(AmortizationSchedule.builder()
                        .loan(loan)
                        .paymentNumber(number)
                        .dueDate(LocalDate.now().plusMonths(number))
                        .scheduledPayment(INSTALMENT)
                        .principalPortion(INSTALMENT)
                        .interestPortion(BigDecimal.ZERO)
                        .remainingBalance(PRINCIPAL.subtract(
                                INSTALMENT.multiply(BigDecimal.valueOf(number))))
                        .status(ScheduleStatus.PENDING)
                        .build());
            }
            return loan.getId();
        });
    }

    private LoanRepaymentRequest repayment() {
        LoanRepaymentRequest request = new LoanRepaymentRequest();
        request.setAmount(INSTALMENT);
        return request;
    }

    private Loan reread() {
        return loanRepository.findById(loanId).orElseThrow();
    }

    private long settledInstalments() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM amortization_schedules WHERE loan_id = ? AND status <> 'PENDING'",
                Long.class, loanId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("a repayment settles the lowest-numbered unpaid instalment")
    void repaymentTakesTheEarliestInstalment() {
        // The query behind this had no ORDER BY, and the caller took the
        // first row as "next unpaid" — which decides the interest and
        // principal split and the payment number recorded against the
        // customer's repayment.
        //
        // Worth being exact about what this test is: a guard, not a
        // reproduction. Removing the ORDER BY and re-running it still passes,
        // even with the rows written in reverse order, so on this engine and
        // this data the unordered query happened to return the right one. An
        // unspecified order is still unspecified — it can change with the
        // plan, the statistics or the version — so the ordering is stated
        // rather than relied on, and this pins it against a future change.
        inTransaction.executeWithoutResult(s -> service.makeRepayment(loanId, repayment()));

        Integer settled = jdbc.queryForObject(
                "SELECT payment_number FROM amortization_schedules "
                        + "WHERE loan_id = ? AND status <> 'PENDING'",
                Integer.class, loanId);
        assertThat(settled).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent repayments each settle their own instalment")
    void concurrentRepaymentsDoNotShareAnInstalment() throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        AtomicInteger accepted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            attempts.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                inTransaction.executeWithoutResult(s -> service.makeRepayment(loanId, repayment()));
                accepted.incrementAndGet();
                return null;
            });
        }

        List<java.util.concurrent.Future<Void>> results = pool.invokeAll(attempts);
        pool.shutdown();
        assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).isTrue();
        for (java.util.concurrent.Future<Void> result : results) {
            // A lost update surfaces here as an optimistic-locking failure
            // rather than being swallowed by the counters.
            result.get();
        }

        assertThat(accepted.get()).isEqualTo(THREADS);
        assertThat(settledInstalments())
                .as("one instalment settled per repayment, never two repayments onto one")
                .isEqualTo(THREADS);
    }

    @Test
    @DisplayName("the balance falls by exactly the principal that was repaid")
    void balanceMatchesTheRepayments() throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            attempts.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                inTransaction.executeWithoutResult(s -> service.makeRepayment(loanId, repayment()));
                return null;
            });
        }
        pool.invokeAll(attempts);
        pool.shutdown();
        assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).isTrue();

        BigDecimal principalRepaid = jdbc.queryForObject(
                "SELECT coalesce(sum(principal_paid), 0) FROM loan_repayments WHERE loan_id = ?",
                BigDecimal.class, loanId);

        Loan loan = reread();
        assertThat(loan.getRemainingBalance())
                .as("a lost update shows up as a balance the repayments do not account for")
                .isEqualByComparingTo(PRINCIPAL.subtract(principalRepaid));
        assertThat(loan.getPaymentsMade())
                .as("and as a payment count that does not match the repayment rows")
                .isEqualTo(THREADS);
    }
}
