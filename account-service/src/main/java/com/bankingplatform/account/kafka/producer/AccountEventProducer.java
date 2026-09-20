package com.bankingplatform.account.kafka.producer;

import com.bankingplatform.common.events.AccountCreated;
import com.bankingplatform.common.events.BalanceUpdated;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.OverdraftTriggered;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
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

    private final OutboxPublisher outbox;

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
     * Records the event in the outbox, in the caller's transaction.
     *
     * <p>This used to call {@code kafkaTemplate.send} and log whatever came
     * back. That could never be reliable: {@code send} is asynchronous, so it
     * returns before the broker has acknowledged anything and the caught
     * exception was not evidence of delivery or of failure. The account could
     * commit while the event never reached Kafka, and every consumer of it —
     * notification, statistics, the user's own card and loan views — would
     * carry on as if the account did not exist.
     *
     * <p>A row in the outbox commits with the account or not at all, and
     * {@code OutboxRelay} sends it afterwards, retrying until the broker takes
     * it. The failure mode moves from silent loss to visible delay.
     */
    private void send(DomainEvent event) {
        outbox.publish(Topics.ACCOUNT_EVENTS, event);
    }
}
