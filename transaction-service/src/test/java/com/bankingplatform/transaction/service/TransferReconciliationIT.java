package com.bankingplatform.transaction.service;

import com.bankingplatform.transaction.client.AccountClient;
import com.bankingplatform.transaction.dto.MovementStatusResponse;
import com.bankingplatform.transaction.dto.TransferRequest;
import com.bankingplatform.transaction.model.TransferAttempt;
import com.bankingplatform.transaction.model.TransferAttemptStatus;
import com.bankingplatform.transaction.repository.TransferAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A transfer that half-applies must leave evidence behind it.
 *
 * <p>The debit lands in another service, over HTTP. If the credit then fails,
 * the local transaction rolls back — and until now that rollback erased the
 * only record that a transfer had been attempted. account-service was short
 * the money and this service had nothing naming the accounts or the amount.
 *
 * <p>Against real PostgreSQL, because what is under test is precisely which
 * writes survive a rollback. A mocked repository cannot show that: it has no
 * transactions to roll back.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TransferReconciliationIT.Recorders.class)
// The recorder's REQUIRES_NEW writes have to commit independently, which a
// test-managed transaction rolling back at the end would hide.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Transfer reconciliation — PostgreSQL integration")
class TransferReconciliationIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transaction_db")
            .withUsername("bankingadmin")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * The recorder as the container builds it, so its {@code REQUIRES_NEW} is
     * actually proxied. A hand-constructed instance carries no proxy and
     * would share whatever transaction happened to be open — which is the one
     * behaviour these tests are about.
     */
    @TestConfiguration
    @EnableTransactionManagement
    static class Recorders {

        @Bean
        TransferAttemptRecorder transferAttemptRecorder(TransferAttemptRepository attempts) {
            return new TransferAttemptRecorder(attempts);
        }
    }

    @Autowired private TransferAttemptRepository attempts;
    @Autowired private TransferAttemptRecorder recorder;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate inTransaction;
    private AccountClient accountClient;
    private TransferReconciler reconciler;

    @BeforeEach
    void setUp() {
        inTransaction = new TransactionTemplate(transactions);
        accountClient = Mockito.mock(AccountClient.class);
        reconciler = new TransferReconciler(attempts, recorder, accountClient);
        // Zero, so the tests do not have to wait out a grace period meant for
        // transfers that are merely in flight.
        ReflectionTestUtils.setField(reconciler, "afterMinutes", 0);
        ReflectionTestUtils.setField(reconciler, "batchSize", 50);

        jdbc.update("DELETE FROM transfer_attempt");
    }

    private TransferRequest request() {
        TransferRequest request = new TransferRequest();
        request.setFromAccountId(11L);
        request.setToAccountId(22L);
        request.setAmount(new BigDecimal("250.00"));
        request.setCurrency("USD");
        return request;
    }

    private void answer(String key, boolean applied, String status) {
        Mockito.when(accountClient.movementStatus("txn-" + key))
                .thenReturn(MovementStatusResponse.builder()
                        .idempotencyKey("txn-" + key).applied(applied).status(status).build());
    }

    @Test
    @DisplayName("the attempt survives the rollback of the transfer that wrote it")
    void theRecordOutlivesTheFailure() {
        // The whole point. The failure that makes the record worth having is
        // the same failure that would roll it back, so the recorder commits
        // outside the caller's transaction.
        assertThatThrownBy(() -> inTransaction.executeWithoutResult(status -> {
            Long attemptId = recorder.started("debit-1", "credit-1", request(), "USD");
            recorder.debited(attemptId);
            throw new IllegalStateException("the credit failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(attempts.findByDebitRef("debit-1"))
                .as("the transfer rolled back; the evidence did not")
                .get()
                .extracting(TransferAttempt::getStatus, TransferAttempt::getFromAccountId,
                        TransferAttempt::getToAccountId, TransferAttempt::getAmount)
                .containsExactly(TransferAttemptStatus.DEBITED, 11L, 22L, new BigDecimal("250.00"));
    }

    @Test
    @DisplayName("a completed transfer settles and is not reported")
    void completedTransfersAreNotReported() {
        Long attemptId = recorder.started("debit-2", "credit-2", request(), "USD");
        recorder.debited(attemptId);
        recorder.completed(attemptId);

        assertThat(reconciler.unsettled()).isEmpty();
    }

    @Test
    @DisplayName("a half-applied transfer is found, and says which account is short")
    void halfAppliedTransfersAreFound() {
        Long attemptId = recorder.started("debit-3", "credit-3", request(), "USD");
        recorder.debited(attemptId);
        recorder.creditFailed(attemptId, "account-service returned 500");

        List<TransferAttempt> unsettled = reconciler.unsettled();

        assertThat(unsettled).hasSize(1);
        assertThat(unsettled.get(0))
                .extracting(TransferAttempt::getStatus, TransferAttempt::getFromAccountId,
                        TransferAttempt::getAmount)
                .containsExactly(TransferAttemptStatus.CREDIT_FAILED, 11L, new BigDecimal("250.00"));
    }

    @Test
    @DisplayName("reconciliation asks account-service and records what each leg did")
    void reconciliationEstablishesTheTruth() {
        Long attemptId = recorder.started("debit-4", "credit-4", request(), "USD");
        recorder.debited(attemptId);
        recorder.creditFailed(attemptId, "timeout");

        answer("debit-4", true, "COMPLETED");
        answer("credit-4", false, "NOT_FOUND");

        assertThat(reconciler.reconcile()).isEqualTo(1);

        assertThat(attempts.findByDebitRef("debit-4")).get().satisfies(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(TransferAttemptStatus.RECONCILED);
            assertThat(attempt.getDebitApplied()).isTrue();
            assertThat(attempt.getCreditApplied()).isFalse();
            assertThat(attempt.getReconciledAt()).isNotNull();
            assertThat(attempt.getNote()).contains("source account is short");
        });
    }

    @Test
    @DisplayName("a transfer that actually completed is recorded as complete, not as damage")
    void bothLegsAppliedIsNotAnIncident() {
        // The caller saw an error -- the credit's response was lost rather
        // than the credit failing. Both legs did apply, and reconciliation
        // has to be able to say so rather than reporting a phantom loss.
        Long attemptId = recorder.started("debit-5", "credit-5", request(), "USD");
        recorder.debited(attemptId);
        recorder.creditFailed(attemptId, "read timeout");

        answer("debit-5", true, "COMPLETED");
        answer("credit-5", true, "COMPLETED");

        reconciler.reconcile();

        assertThat(attempts.findByDebitRef("debit-5")).get().satisfies(attempt -> {
            assertThat(attempt.getDebitApplied()).isTrue();
            assertThat(attempt.getCreditApplied()).isTrue();
            assertThat(attempt.getNote()).contains("Both legs applied");
        });
    }

    @Test
    @DisplayName("a transfer where nothing moved is safe to reissue, and says so")
    void neitherLegAppliedIsReissuable() {
        Long attemptId = recorder.started("debit-6", "credit-6", request(), "USD");

        answer("debit-6", false, "NOT_FOUND");
        answer("credit-6", false, "NOT_FOUND");

        reconciler.reconcile();

        assertThat(attempts.findByDebitRef("debit-6")).get().satisfies(attempt -> {
            assertThat(attempt.getDebitApplied()).isFalse();
            assertThat(attempt.getCreditApplied()).isFalse();
            assertThat(attempt.getNote()).contains("may be reissued");
        });
    }

    @Test
    @DisplayName("an unreachable account-service leaves the attempt for the next pass")
    void anUnreachableAccountServiceIsNotAVerdict() {
        // account-service being down is usually why the attempt is unsettled.
        // Recording "not applied" because the question could not be asked
        // would be inventing a verdict.
        Long attemptId = recorder.started("debit-7", "credit-7", request(), "USD");
        recorder.debited(attemptId);
        Mockito.when(accountClient.movementStatus(Mockito.anyString()))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThat(reconciler.reconcile()).isZero();
        assertThat(attempts.findByDebitRef("debit-7")).get()
                .extracting(TransferAttempt::getStatus)
                .isEqualTo(TransferAttemptStatus.DEBITED);
    }

    @Test
    @DisplayName("a transfer still in flight is not reported as stuck")
    void inFlightTransfersAreLeftAlone() {
        ReflectionTestUtils.setField(reconciler, "afterMinutes", 5);
        Long attemptId = recorder.started("debit-8", "credit-8", request(), "USD");
        recorder.debited(attemptId);

        assertThat(reconciler.unsettled())
                .as("a transfer takes milliseconds; the grace period is for the ones that are merely slow")
                .isEmpty();

        jdbc.update("UPDATE transfer_attempt SET created_at = ? WHERE debit_ref = 'debit-8'",
                LocalDateTime.now().minusMinutes(30));
        assertThat(reconciler.unsettled()).hasSize(1);
    }
}
