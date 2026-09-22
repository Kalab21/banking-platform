package com.bankingplatform.fraud.service;

import com.bankingplatform.fraud.client.AccountClient;
import com.bankingplatform.fraud.model.AlertStatus;
import com.bankingplatform.fraud.model.FraudAlert;
import com.bankingplatform.fraud.model.FraudRulesAudit;
import com.bankingplatform.fraud.exception.FraudCounterUnreadableException;
import com.bankingplatform.fraud.repository.FraudAlertRepository;
import com.bankingplatform.fraud.repository.FraudRulesAuditRepository;
import com.bankingplatform.common.events.FraudAlertCreated;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {

    private final FraudAlertRepository alertRepo;
    private final FraudRulesAuditRepository auditRepo;
    private final StringRedisTemplate redisTemplate;
    private final AccountClient accountClient;
    private final OutboxPublisher outbox;

    @Value("${fraud.rules.high-amount-threshold:10000}")
    private BigDecimal highAmountThreshold;

    @Value("${fraud.rules.critical-amount-threshold:25000}")
    private BigDecimal criticalAmountThreshold;

    @Value("${fraud.rules.velocity-window-seconds:3600}")
    private long velocityWindowSeconds;

    @Value("${fraud.rules.velocity-max-transactions:5}")
    private int velocityMaxTransactions;


    @Value("${fraud.rules.failed-payment-threshold:3}")
    private int failedPaymentThreshold;

    @Value("${fraud.rules.alert-score-threshold:50}")
    private int alertScoreThreshold;

    @Value("${fraud.rules.freeze-score-threshold:80}")
    private int freezeScoreThreshold;

    // ---- public entry points ----

    /**
     * @param eventId the publication this evaluation is for, used to keep the
     *                Redis counters from advancing twice on a redelivery
     */
    public void evaluateTransaction(Long accountId, Long userId, BigDecimal amount, String txRef,
                                    String eventId) {
        int score = 0;
        StringBuilder desc = new StringBuilder();

        if (amount != null && amount.compareTo(criticalAmountThreshold) > 0) {
            score += 60;
            desc.append("Critical-amount transaction ($").append(amount).append("). ");
            saveAudit(accountId, "CRITICAL_AMOUNT", 60, score, txRef);
        } else if (amount != null && amount.compareTo(highAmountThreshold) > 0) {
            score += 40;
            desc.append("High-amount transaction ($").append(amount).append("). ");
            saveAudit(accountId, "HIGH_AMOUNT", 40, score, txRef);
        }

        int velocity = incrementVelocityCounter(accountId, eventId);
        if (velocity > velocityMaxTransactions) {
            score += 30;
            desc.append("High velocity: ").append(velocity).append(" transactions in 1 hour. ");
            saveAudit(accountId, "HIGH_VELOCITY", 30, score, txRef);
        }

        if (score >= alertScoreThreshold) {
            createAlert(accountId, userId, "TRANSACTION_FRAUD", score,
                    desc.toString().trim(), txRef, "TRANSACTION", amount);
        }

        if (score >= freezeScoreThreshold) {
            // After the transaction commits, not inside it. Freezing calls
            // account-service, and holding a database connection open across a
            // remote call is how a listener thread pool starves a connection
            // pool when that service is slow.
            int finalScore = score;
            afterCommit(() -> freezeAccount(accountId, finalScore));
        }
    }

    public void evaluateFailedPayment(Long accountId, Long userId, String eventId) {
        long failedCount = incrementFailedPaymentCounter(accountId, eventId);
        if (failedCount >= failedPaymentThreshold) {
            int score = 25;
            String desc = "Multiple failed payments: " + failedCount + " failures detected.";
            saveAudit(accountId, "MULTIPLE_FAILED_PAYMENTS", score, score, null);
            createAlert(accountId, userId, "PAYMENT_FRAUD", score, desc, null, "PAYMENT", null);
        }
    }

    // ---- query methods ----

    public Page<FraudAlert> getAlertsByAccount(Long accountId, int page, int size) {
        return alertRepo.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, size));
    }

    public List<FraudAlert> getOpenAlerts() {
        return alertRepo.findByStatusOrderByCreatedAtDesc(AlertStatus.OPEN);
    }

    public FraudAlert reviewAlert(Long alertId, AlertStatus newStatus, Long reviewedBy, String note) {
        FraudAlert alert = alertRepo.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alert not found: " + alertId));
        alert.setStatus(newStatus);
        alert.setReviewedBy(reviewedBy);
        alert.setResolutionNote(note);
        alert.setReviewedAt(java.time.LocalDateTime.now());
        return alertRepo.save(alert);
    }

    // ---- private helpers ----

    /**
     * Counts one transaction against the account, once per event.
     *
     * <p>Redis is not part of the database transaction, so a redelivery would
     * otherwise advance this counter again — and the processed-event guard
     * cannot help, because it rolls back with the transaction it lives in.
     * Four delivery attempts of one transaction would look like four
     * transactions, which is enough on its own to cross the velocity
     * threshold, raise a fraud alert and freeze a live customer's account.
     *
     * <p>So the increment is claimed per event first: a marker set only if
     * absent, expiring with the window it belongs to. A retry finds the marker
     * and reads the current count without adding to it.
     */
    private int incrementVelocityCounter(Long accountId, String eventId) {
        String key = "fraud:velocity:" + accountId;
        if (!firstTimeCounting(key, eventId, Duration.ofSeconds(velocityWindowSeconds))) {
            long counted = readCounter(key, "velocity");
            if (counted > Integer.MAX_VALUE) {
                throw unreadable("velocity", key, null);
            }
            return (int) counted;
        }
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(velocityWindowSeconds));
        }
        return count != null ? count.intValue() : 1;
    }

    /**
     * True the first time this event is counted against this key.
     *
     * <p>An event with no id cannot be de-duplicated, so it is counted — the
     * alternative is never counting it at all.
     */
    private boolean firstTimeCounting(String key, String eventId, Duration window) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent("counted:" + key + ":" + eventId, "1", window);
        return !Boolean.FALSE.equals(claimed);
    }

    /** Runs after the surrounding transaction commits, or inline if there is none. */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private long incrementFailedPaymentCounter(Long accountId, String eventId) {
        String key = "fraud:failed-payments:" + accountId;
        if (!firstTimeCounting(key, eventId, Duration.ofHours(24))) {
            return readCounter(key, "failed-payment");
        }
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofHours(24));
        }
        return count != null ? count : 1L;
    }

    /**
     * The count already recorded for this key, for an event counted before.
     *
     * <p>A missing key is not corruption. The counter carries the window's TTL,
     * so it disappears when the window closes; reading one is then honestly the
     * first of a new window, which is what the increment path would have
     * produced anyway.
     *
     * <p>A value that is not a number, or is negative, is different in kind:
     * this service writes it only through INCR, so it can be neither. Both
     * refuse rather than guess. The refusal matters most where it is least
     * obvious — parsing the corruption away as a low count would leave the
     * velocity rule running against an account with the rule effectively off,
     * which is silent, indefinite, and looks exactly like a quiet customer.
     */
    private long readCounter(String key, String counterType) {
        String current = redisTemplate.opsForValue().get(key);
        if (current == null) {
            return 1;
        }
        long counted;
        try {
            counted = Long.parseLong(current.trim());
        } catch (NumberFormatException malformed) {
            throw unreadable(counterType, key, malformed);
        }
        if (counted < 0) {
            throw unreadable(counterType, key, null);
        }
        return counted;
    }

    /**
     * Names the counter and the key, and nothing from the event.
     *
     * <p>The key is the operationally useful half: it identifies which counter
     * to look at. The record that triggered this reaches the dead-letter topic
     * intact, so the log does not need to repeat its contents.
     */
    private FraudCounterUnreadableException unreadable(String counterType, String key,
                                                       NumberFormatException cause) {
        log.error("Fraud {} counter at {} is not a usable count; refusing to score this event ({})",
                counterType, key, cause == null ? "negative value" : cause.getClass().getSimpleName());
        return new FraudCounterUnreadableException(
                "Fraud " + counterType + " counter could not be read", cause);
    }

    private void createAlert(Long accountId, Long userId, String alertType, int score,
                             String description, String eventRef, String eventType, BigDecimal amount) {
        FraudAlert alert = FraudAlert.builder()
                .accountId(accountId)
                .userId(userId)
                .alertType(alertType)
                .riskScore(score)
                .description(description)
                .eventRef(eventRef)
                .eventType(eventType)
                .amount(amount)
                .status(AlertStatus.OPEN)
                .build();
        alertRepo.save(alert);
        log.warn("FRAUD ALERT created: accountId={} score={} type={}", accountId, score, alertType);

        publishFraudAlert(alert);
    }

    private void saveAudit(Long accountId, String ruleName, int points, int total, String eventRef) {
        auditRepo.save(FraudRulesAudit.builder()
                .accountId(accountId)
                .ruleName(ruleName)
                .pointsAdded(points)
                .totalScore(total)
                .eventRef(eventRef)
                .build());
    }

    private void freezeAccount(Long accountId, int score) {
        try {
            accountClient.updateStatus(accountId, "FROZEN");
            log.warn("Account FROZEN due to fraud risk: accountId={} score={}", accountId, score);
        } catch (Exception e) {
            log.error("Failed to freeze account {}: {}", accountId, e.getMessage());
        }
    }

    /**
     * Records the alert in the outbox, in the caller's transaction.
     *
     * <p>Nothing consumes {@code fraud-alert-events} today; that is recorded
     * in {@code docs/EVENTS.md} rather than resolved by inventing a consumer.
     * It is still written reliably — an alert that was raised and never
     * announced is the one record a review queue or an audit trail cannot be
     * given after the fact.
     *
     * <p>The caller's transaction is the listener's: this runs inside the
     * consumer that is processing the transaction event, alongside the
     * processed-event claim. So the alert row, the claim and the publication
     * all commit together, and a redelivery reproduces none of them twice.
     *
     * <p>The record is keyed by account. It was sent with no key at all, so
     * alerts round-robined across partitions and two alerts on one account
     * could reach any future consumer out of order.
     */
    private void publishFraudAlert(FraudAlert alert) {
        FraudAlertCreated event = FraudAlertCreated.of(alert.getId(), alert.getAccountId(),
                alert.getUserId(), alert.getAlertType(), alert.getRiskScore());
        outbox.publish(Topics.FRAUD_ALERT_EVENTS, event);
    }
}
