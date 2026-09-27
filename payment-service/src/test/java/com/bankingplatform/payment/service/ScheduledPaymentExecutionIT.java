package com.bankingplatform.payment.service;

import com.bankingplatform.common.security.CallerContext;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.common.security.Role;
import com.bankingplatform.payment.client.AccountClient;
import com.bankingplatform.payment.client.TransactionClient;
import com.bankingplatform.payment.dto.AccountResponse;
import com.bankingplatform.payment.exception.PaymentException;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Executing a claimed scheduled payment, against a real PostgreSQL.
 *
 * <p>Scheduled INTERNAL payments never worked: the job has no HTTP request, so
 * the transfer reached transaction-service with no caller identity and was
 * refused, and every due payment was marked FAILED. The claiming was tested;
 * the execution was not, because the transfer client was mocked without
 * asking who it was called as.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Scheduled payment execution — PostgreSQL integration")
class ScheduledPaymentExecutionIT {

    private static final long PAYER = 7L;

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

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private PaymentServiceImpl service;
    private TransactionClient transactionClient;
    private TransactionTemplate inTransaction;
    private final List<Optional<CallerIdentity>> transferCallers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transactionClient = Mockito.mock(TransactionClient.class);
        AccountClient accountClient = Mockito.mock(AccountClient.class);
        AccountResponse payerAccount = new AccountResponse();
        payerAccount.setUserId(PAYER);
        when(accountClient.getAccountInternal(9L)).thenReturn(payerAccount);
        // Record who each transfer is made as: that is what transaction-service
        // authorizes.
        when(transactionClient.transfer(anyString(), any())).thenAnswer(invocation -> {
            transferCallers.add(CallerContext.current());
            return null;
        });

        service = new PaymentServiceImpl(
                paymentRepository, auditLogRepository,
                Mockito.mock(PaymentMapper.class),
                Mockito.mock(PaymentEventProducer.class),
                transactionClient, accountClient);
        ReflectionTestUtils.setField(service, "stalledAfterMinutes", 15);
        inTransaction = new TransactionTemplate(transactions);
        jdbc.update("DELETE FROM payments");
        transferCallers.clear();
    }

    private long payment(String ref, String status) {
        return payment(ref, status, "INTERNAL");
    }

    private long payment(String ref, String status, String type) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO payments
                    (payment_ref, payer_account_id, payee_account_id, payment_type, amount,
                     currency, status, is_recurring, scheduled_at, created_at, updated_at)
                VALUES (?, 9, 10, ?, 25.00, 'USD', ?, false, ?, now(), ?)
                """, ref, type, status, Timestamp.valueOf(now.minusMinutes(1)), Timestamp.valueOf(now));
        Long id = jdbc.queryForObject("SELECT id FROM payments WHERE payment_ref = ?", Long.class, ref);
        return id == null ? 0 : id;
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("a claimed payment's transfer is made as the customer who owns the paying account")
    void transferRunsAsThePayer() {
        long id = payment("exec-1", "PENDING");
        List<Long> claimed = inTransaction.execute(s -> service.claimScheduledPayments(10));
        assertThat(claimed).containsExactly(id);

        inTransaction.executeWithoutResult(s -> service.processClaimedPayment(id));

        assertThat(transferCallers).hasSize(1);
        CallerIdentity caller = transferCallers.get(0).orElseThrow();
        assertThat(caller.userId()).isEqualTo(PAYER);
        assertThat(caller.role()).isEqualTo(Role.CUSTOMER);
        assertThat(status(id)).isEqualTo("COMPLETED");
        // The identity does not outlive the payment it was set for.
        assertThat(CallerContext.current()).isEmpty();
    }

    @Test
    @DisplayName("a payment the scheduler has claimed cannot then be cancelled")
    void claimedPaymentCannotBeCancelled() {
        long id = payment("exec-2", "PENDING");
        inTransaction.execute(s -> service.claimScheduledPayments(10));

        assertThatThrownBy(() -> inTransaction.executeWithoutResult(s -> service.cancel(id)))
                .isInstanceOf(PaymentException.class);
        assertThat(status(id)).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("a bill payment debits the payer before it is reported complete")
    void externalPaymentDebitsThePayer() {
        // Non-internal payments used to be marked COMPLETED, and announced as
        // paid, with the payer's balance never touched.
        long id = payment("exec-bill", "PENDING", "BILL");
        inTransaction.execute(s -> service.claimScheduledPayments(10));

        inTransaction.executeWithoutResult(s -> service.processClaimedPayment(id));

        verify(transactionClient).withdraw(org.mockito.ArgumentMatchers.eq("payment-exec-bill"),
                org.mockito.ArgumentMatchers.argThat(w -> w.getAccountId() == 9L
                        && w.getAmount().compareTo(new java.math.BigDecimal("25.00")) == 0));
        verify(transactionClient, never()).transfer(anyString(), any());
        assertThat(status(id)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a failed debit leaves a bill payment FAILED, not COMPLETED")
    void refusedDebitFailsThePayment() {
        when(transactionClient.withdraw(anyString(), any())).thenThrow(new IllegalStateException("insufficient funds"));
        long id = payment("exec-bill-2", "PENDING", "BILL");
        inTransaction.execute(s -> service.claimScheduledPayments(10));

        inTransaction.executeWithoutResult(s -> service.processClaimedPayment(id));

        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a payment cancelled before it is processed is not executed")
    void cancelledPaymentIsNotExecuted() {
        long id = payment("exec-3", "CANCELLED");

        inTransaction.executeWithoutResult(s -> service.processClaimedPayment(id));

        verify(transactionClient, never()).transfer(anyString(), any());
        assertThat(status(id)).isEqualTo("CANCELLED");
    }
}
