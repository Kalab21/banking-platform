package com.bankingplatform.transaction.kafka.producer;

import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Publishes money movement.
 *
 * <p>Both events now carry the owning user. Every consumer of this topic —
 * notification, statistics and fraud — reads {@code userId} before it does
 * anything, and none of them ever received it, so all three had been
 * silently doing nothing. The owner comes from the account-service response
 * the service already holds, so no extra call was needed to supply it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishTransactionCreated(Long transactionId, Long accountId, Long userId,
                                          String type, BigDecimal amount,
                                          BigDecimal balanceAfter, String ref) {
        send(TransactionCreated.of(transactionId, ref, accountId, userId, type, amount, balanceAfter));
        log.info("Published TRANSACTION_CREATED: ref={}, type={}, amount={}", ref, type, amount);
    }

    public void publishTransferCompleted(String debitRef, String creditRef, Long fromAccountId,
                                         Long fromUserId, Long toAccountId, BigDecimal amount) {
        send(TransferCompleted.of(debitRef, creditRef, fromAccountId, fromUserId, toAccountId, amount));
        log.info("Published TRANSFER_COMPLETED: from={} to={} amount={}", fromAccountId, toAccountId, amount);
    }

    /**
     * Keyed by account rather than by transaction reference.
     *
     * <p>A reference is unique per event, so keying on it put one account's
     * transactions on every partition and gave up the only ordering Kafka
     * offers. Consumers that care about sequence on an account now get it.
     */
    private void send(DomainEvent event) {
        try {
            kafkaTemplate.send(Topics.TRANSACTION_EVENTS, event.partitionKey(), event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — {} not published for key {}: {}",
                    event.eventType(), event.partitionKey(), e.getMessage());
        }
    }
}
