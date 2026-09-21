package com.bankingplatform.common.kafka.outbox;

import com.bankingplatform.common.events.DomainEvent;

/**
 * Records an event for publication as part of the caller's transaction.
 *
 * <p>The point of the indirection is that this is a database write, not a
 * network call. It commits with the business change or not at all, which is
 * the guarantee {@code kafkaTemplate.send} cannot give: {@code send} is
 * asynchronous, so it returns before the broker has acknowledged anything, and
 * a producer that catches its exception has caught nothing meaningful.
 */
public interface OutboxPublisher {

    /**
     * Stores the event, to be sent by the relay once the transaction commits.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         if there is no surrounding transaction to commit with
     */
    void publish(String topic, DomainEvent event);
}
