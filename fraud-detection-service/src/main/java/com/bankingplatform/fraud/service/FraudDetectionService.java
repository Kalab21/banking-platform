package com.bankingplatform.fraud.service;

import com.bankingplatform.fraud.client.AccountClient;
import com.bankingplatform.fraud.model.AlertStatus;
import com.bankingplatform.fraud.model.FraudAlert;
import com.bankingplatform.fraud.model.FraudRulesAudit;
import com.bankingplatform.fraud.repository.FraudAlertRepository;
import com.bankingplatform.fraud.repository.FraudRulesAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${fraud.rules.high-amount-threshold:10000}")
    private BigDecimal highAmountThreshold;

    @Value("${fraud.rules.critical-amount-threshold:25000}")
    private BigDecimal criticalAmountThreshold;

    @Value("${fraud.rules.velocity-window-seconds:3600}")
    private long velocityWindowSeconds;

    @Value("${fraud.rules.velocity-max-transactions:5}")
    private int velocityMaxTransactions;

    @Value("${fraud.rules.cc-single-purchase-threshold:5000}")
    private BigDecimal ccSinglePurchaseThreshold;

    @Value("${fraud.rules.failed-payment-threshold:3}")
    private int failedPaymentThreshold;

    @Value("${fraud.rules.alert-score-threshold:50}")
    private int alertScoreThreshold;

    @Value("${fraud.rules.freeze-score-threshold:80}")
    private int freezeScoreThreshold;

    // ---- public entry points ----

    public void evaluateTransaction(Long accountId, Long userId, BigDecimal amount, String txRef) {
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

        int velocity = incrementVelocityCounter(accountId);
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
            freezeAccount(accountId, score);
        }
    }

    public void evaluateCreditCardPurchase(Long accountId, Long userId, BigDecimal amount, String txRef) {
        int score = 0;
        StringBuilder desc = new StringBuilder();

        if (amount != null && amount.compareTo(ccSinglePurchaseThreshold) > 0) {
            score += 35;
            desc.append("Large CC purchase ($").append(amount).append("). ");
            saveAudit(accountId, "LARGE_CC_PURCHASE", 35, score, txRef);
        }

        int velocity = incrementVelocityCounter(accountId);
        if (velocity > velocityMaxTransactions) {
            score += 30;
            desc.append("High velocity: ").append(velocity).append(" transactions in 1 hour. ");
            saveAudit(accountId, "HIGH_VELOCITY", 30, score, txRef);
        }

        if (score >= alertScoreThreshold) {
            createAlert(accountId, userId, "CC_FRAUD", score,
                    desc.toString().trim(), txRef, "CREDIT_CARD_TRANSACTION", amount);
        }

        if (score >= freezeScoreThreshold) {
            freezeAccount(accountId, score);
        }
    }

    public void evaluateFailedPayment(Long accountId, Long userId) {
        long failedCount = incrementFailedPaymentCounter(accountId);
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

    private int incrementVelocityCounter(Long accountId) {
        String key = "fraud:velocity:" + accountId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(velocityWindowSeconds));
        }
        return count != null ? count.intValue() : 1;
    }

    private long incrementFailedPaymentCounter(Long accountId) {
        String key = "fraud:failed-payments:" + accountId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofHours(24));
        }
        return count != null ? count : 1L;
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

    private void publishFraudAlert(FraudAlert alert) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "FRAUD_ALERT_CREATED");
            event.put("alertId", alert.getId());
            event.put("accountId", alert.getAccountId());
            event.put("userId", alert.getUserId());
            event.put("alertType", alert.getAlertType());
            event.put("riskScore", alert.getRiskScore());
            kafkaTemplate.send("fraud-alert-events", event);
        } catch (Exception e) {
            log.error("Failed to publish fraud alert event: {}", e.getMessage());
        }
    }
}
