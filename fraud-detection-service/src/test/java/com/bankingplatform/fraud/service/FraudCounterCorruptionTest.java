package com.bankingplatform.fraud.service;

import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
import com.bankingplatform.fraud.client.AccountClient;
import com.bankingplatform.fraud.exception.FraudCounterUnreadableException;
import com.bankingplatform.fraud.model.FraudAlert;
import com.bankingplatform.fraud.repository.FraudAlertRepository;
import com.bankingplatform.fraud.repository.FraudRulesAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the fraud counters do when Redis holds something this service did not
 * write.
 *
 * <p>These drive the redelivery branch on purpose. A counter is only ever
 * <em>read</em> when the event has been counted before — the first delivery
 * uses INCR and never parses anything — so the corrupted-state path is
 * reachable exactly when a retry arrives, which is the ordinary case under a
 * dead-letter policy rather than an exotic one.
 */
@DisplayName("Fraud counters, when the stored count is not a count")
class FraudCounterCorruptionTest {

    private static final long ACCOUNT = 77L;
    private static final long USER = 42L;
    private static final String VELOCITY_KEY = "fraud:velocity:" + ACCOUNT;
    private static final String FAILED_KEY = "fraud:failed-payments:" + ACCOUNT;

    private ValueOperations<String, String> values;
    private FraudAlertRepository alerts;
    private FraudDetectionService fraud;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        alerts = mock(FraudAlertRepository.class);
        when(alerts.save(any(FraudAlert.class))).thenAnswer(call -> call.getArgument(0));

        fraud = new FraudDetectionService(alerts, mock(FraudRulesAuditRepository.class),
                redis, mock(AccountClient.class), mock(OutboxPublisher.class));

        ReflectionTestUtils.setField(fraud, "highAmountThreshold", new BigDecimal("10000"));
        ReflectionTestUtils.setField(fraud, "criticalAmountThreshold", new BigDecimal("25000"));
        ReflectionTestUtils.setField(fraud, "velocityWindowSeconds", 3600L);
        ReflectionTestUtils.setField(fraud, "velocityMaxTransactions", 5);
        ReflectionTestUtils.setField(fraud, "failedPaymentThreshold", 3);
        ReflectionTestUtils.setField(fraud, "alertScoreThreshold", 50);
        ReflectionTestUtils.setField(fraud, "freezeScoreThreshold", 80);
    }

    /** Makes this delivery a redelivery, so the counter is read rather than incremented. */
    private void alreadyCounted() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);
    }

    /** Writes a raw value under the real counter key, as only a bug or an intruder would. */
    private void storedVelocity(String value) {
        when(values.get(VELOCITY_KEY)).thenReturn(value);
    }

    private void storedFailedPayments(String value) {
        when(values.get(FAILED_KEY)).thenReturn(value);
    }

    private void evaluateOrdinaryTransaction() {
        fraud.evaluateTransaction(ACCOUNT, USER, new BigDecimal("25.00"), "ref-1", "event-1");
    }

    @Nested
    @DisplayName("the velocity counter")
    class Velocity {

        @Test
        @DisplayName("a value that is not a number stops the event rather than scoring it")
        void malformedRefusesToScore() {
            alreadyCounted();
            storedVelocity("not-a-number");

            assertThatThrownBy(FraudCounterCorruptionTest.this::evaluateOrdinaryTransaction)
                    .isInstanceOf(FraudCounterUnreadableException.class);
        }

        @Test
        @DisplayName("a corrupted counter does not quietly become a low count")
        void malformedIsNotAnUndercount() {
            // The failure that matters most, because it is invisible. Parsing
            // the corruption away as a small number leaves the velocity rule
            // running with the rule effectively switched off for this account,
            // and a switched-off control looks exactly like a quiet customer.
            alreadyCounted();
            storedVelocity("not-a-number");

            assertThatThrownBy(FraudCounterCorruptionTest.this::evaluateOrdinaryTransaction)
                    .isInstanceOf(FraudCounterUnreadableException.class);

            verify(alerts, never()).save(any(FraudAlert.class));
        }

        @Test
        @DisplayName("a corrupted counter does not invent fraud evidence against the customer")
        void malformedDoesNotFabricateAnAlert() {
            // The opposite reading, and the reason this is not solved by
            // defaulting high: a storage fault must not become a recorded
            // claim that this customer was moving money too fast.
            alreadyCounted();
            storedVelocity("999999");

            evaluateOrdinaryTransaction();

            verify(alerts, never()).save(any(FraudAlert.class));
        }

        @Test
        @DisplayName("a negative count is refused too, not treated as room to spare")
        void negativeRefused() {
            alreadyCounted();
            storedVelocity("-4");

            assertThatThrownBy(FraudCounterCorruptionTest.this::evaluateOrdinaryTransaction)
                    .isInstanceOf(FraudCounterUnreadableException.class);
        }

        @Test
        @DisplayName("a count too large to be a transaction count is refused")
        void aboveIntegerRangeRefused() {
            alreadyCounted();
            storedVelocity("2147483648");

            assertThatThrownBy(FraudCounterCorruptionTest.this::evaluateOrdinaryTransaction)
                    .isInstanceOf(FraudCounterUnreadableException.class);
        }

        @Test
        @DisplayName("a missing counter is a closed window, not corruption")
        void missingCounterIsNotCorruption() {
            // The counter carries the window's TTL, so its absence is the
            // window having closed. That is the one reading that is honestly
            // low, and it matches what the increment path would have produced.
            alreadyCounted();
            storedVelocity(null);

            assertThatCode(FraudCounterCorruptionTest.this::evaluateOrdinaryTransaction)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an ordinary count still scores, so the guard has not become a blanket refusal")
        void validCounterStillWorks() {
            alreadyCounted();
            storedVelocity("3");

            assertThatCode(FraudCounterCorruptionTest.this::evaluateOrdinaryTransaction)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a count over the policy still raises the alert it is supposed to")
        void validHighCounterStillAlerts() {
            // The counterpart to refusing corruption: the rule has to still
            // fire on a real count, or the fix would have disabled the control
            // it was written to protect.
            alreadyCounted();
            storedVelocity("9");

            fraud.evaluateTransaction(ACCOUNT, USER, new BigDecimal("15000.00"), "ref-2", "event-2");

            verify(alerts).save(any(FraudAlert.class));
        }
    }

    @Nested
    @DisplayName("the failed-payment counter")
    class FailedPayments {

        @Test
        @DisplayName("a value that is not a number stops the event rather than scoring it")
        void malformedRefusesToScore() {
            alreadyCounted();
            storedFailedPayments("corrupt");

            assertThatThrownBy(() -> fraud.evaluateFailedPayment(ACCOUNT, USER, "event-3"))
                    .isInstanceOf(FraudCounterUnreadableException.class);

            verify(alerts, never()).save(any(FraudAlert.class));
        }

        @Test
        @DisplayName("a negative count is refused")
        void negativeRefused() {
            alreadyCounted();
            storedFailedPayments("-1");

            assertThatThrownBy(() -> fraud.evaluateFailedPayment(ACCOUNT, USER, "event-4"))
                    .isInstanceOf(FraudCounterUnreadableException.class);
        }

        @Test
        @DisplayName("a long count beyond int range is still a usable failure count")
        void largeLongCountIsFine() {
            // This counter is a long and is compared against a threshold, so
            // unlike the velocity count there is nothing to narrow and no
            // reason to refuse a large value.
            alreadyCounted();
            storedFailedPayments("5000000000");

            assertThatCode(() -> fraud.evaluateFailedPayment(ACCOUNT, USER, "event-5"))
                    .doesNotThrowAnyException();

            verify(alerts).save(any(FraudAlert.class));
        }

        @Test
        @DisplayName("an ordinary count below the threshold raises nothing")
        void belowThreshold() {
            alreadyCounted();
            storedFailedPayments("1");

            fraud.evaluateFailedPayment(ACCOUNT, USER, "event-6");

            verify(alerts, never()).save(any(FraudAlert.class));
        }
    }

    @Test
    @DisplayName("the refusal is the controlled one, so retry and dead-lettering still apply")
    void refusalIsControlledNotRaw() {
        // The container's error handler treats deserialization faults as fatal
        // and everything else as retryable. A raw NumberFormatException would
        // still retry, but it would reach the dead-letter topic describing a
        // parsing accident rather than a corrupted counter, which is what an
        // operator has to act on.
        alreadyCounted();
        storedVelocity("nonsense");

        assertThatThrownBy(this::evaluateOrdinaryTransaction)
                .isInstanceOf(FraudCounterUnreadableException.class)
                .hasMessageContaining("velocity")
                .satisfies(thrown -> assertThat(thrown.getCause())
                        .as("keeps the parse failure as the cause")
                        .isInstanceOf(NumberFormatException.class));
    }
}
