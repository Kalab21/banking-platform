package com.bankingplatform.account.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountEventProducer {

    private static final String TOPIC = "account-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishAccountCreated(Long accountId, Long userId, String accountNumber, String accountType) {
        send(accountId, Map.of(
                "eventType", "ACCOUNT_CREATED",
                "accountId", accountId,
                "userId", userId,
                "accountNumber", accountNumber,
                "accountType", accountType,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published ACCOUNT_CREATED event for account {}", accountId);
    }

    public void publishBalanceUpdated(Long accountId, Long userId, BigDecimal newBalance, String operation) {
        send(accountId, Map.of(
                "eventType", "BALANCE_UPDATED",
                "accountId", accountId,
                "userId", userId,
                "newBalance", newBalance,
                "operation", operation,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    public void publishOverdraftTriggered(Long accountId, Long userId, BigDecimal overdraftAmount) {
        send(accountId, Map.of(
                "eventType", "OVERDRAFT_TRIGGERED",
                "accountId", accountId,
                "userId", userId,
                "overdraftAmount", overdraftAmount,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.warn("Published OVERDRAFT_TRIGGERED event for account {}", accountId);
    }

    private void send(Long key, Map<String, Object> event) {
        try {
            kafkaTemplate.send(TOPIC, key.toString(), event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — event not published for key {}: {}", key, e.getMessage());
        }
    }
}
