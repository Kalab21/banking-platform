package com.bankingplatform.transaction.kafka.producer;

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
public class TransactionEventProducer {

    private static final String TOPIC = "transaction-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishTransactionCreated(Long transactionId, Long accountId,
                                          String type, BigDecimal amount,
                                          BigDecimal balanceAfter, String ref) {
        send(ref, Map.of(
                "eventType", "TRANSACTION_CREATED",
                "transactionId", transactionId,
                "transactionRef", ref,
                "accountId", accountId,
                "type", type,
                "amount", amount,
                "balanceAfter", balanceAfter,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published TRANSACTION_CREATED: ref={}, type={}, amount={}", ref, type, amount);
    }

    public void publishTransferCompleted(String debitRef, String creditRef,
                                         Long fromAccountId, Long toAccountId, BigDecimal amount) {
        send(debitRef, Map.of(
                "eventType", "TRANSFER_COMPLETED",
                "debitRef", debitRef,
                "creditRef", creditRef,
                "fromAccountId", fromAccountId,
                "toAccountId", toAccountId,
                "amount", amount,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published TRANSFER_COMPLETED: from={} to={} amount={}", fromAccountId, toAccountId, amount);
    }

    private void send(String key, Map<String, Object> event) {
        try {
            kafkaTemplate.send(TOPIC, key, event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — event not published for key {}: {}", key, e.getMessage());
        }
    }
}
