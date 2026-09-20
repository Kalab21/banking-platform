package com.bankingplatform.account.kafka.producer;

import com.bankingplatform.common.events.AccountCreated;
import com.bankingplatform.common.events.BalanceUpdated;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.OverdraftTriggered;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Publishes what happened to an account.
 *
 * <p>The events are the shared types in {@code common-events} rather than maps
 * built here, so a field a consumer needs cannot be forgotten at this call
 * site — the constructor will not let it be.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishAccountCreated(Long accountId, Long userId, String accountType) {
        send(AccountCreated.of(accountId, userId, accountType));
        log.info("Published ACCOUNT_CREATED event for account {}", accountId);
    }

    public void publishBalanceUpdated(Long accountId, Long userId, BigDecimal newBalance, String operation) {
        send(BalanceUpdated.of(accountId, userId, newBalance, operation));
    }

    public void publishOverdraftTriggered(Long accountId, Long userId, BigDecimal overdraftAmount) {
        send(OverdraftTriggered.of(accountId, userId, overdraftAmount));
        log.warn("Published OVERDRAFT_TRIGGERED event for account {}", accountId);
    }

    /**
     * Sends on the event's own key, so ordering follows the aggregate.
     *
     * <p>The swallowed exception is a known gap, not an oversight: a caught
     * {@code send} failure is not a delivery guarantee anyway, because
     * {@code send} is asynchronous and returns before the broker has
     * acknowledged anything. Making this reliable needs the database write and
     * the publication to commit together, which is the transactional outbox
     * this project takes on separately. Until then a failure is logged rather
     * than failing a business operation that has already committed.
     */
    private void send(DomainEvent event) {
        try {
            kafkaTemplate.send(Topics.ACCOUNT_EVENTS, event.partitionKey(), event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — {} not published for key {}: {}",
                    event.eventType(), event.partitionKey(), e.getMessage());
        }
    }
}
