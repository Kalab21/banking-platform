package com.bankingplatform.common.kafka.inbox;

/**
 * Answers "have I already handled this event?" — once, atomically.
 *
 * <p>Kafka delivers at least once. A consumer can commit its database work and
 * then die before the offset commit reaches the broker, and the next poll
 * hands it the same record again. Retry, which this platform now does
 * deliberately, makes redelivery ordinary rather than exceptional.
 *
 * <p>So every consumer whose handling has a durable effect needs to recognise
 * a record it has already acted on. The event id is the identity: it is minted
 * once per publication, so two deliveries of one publication share it while
 * two genuine events never do.
 *
 * <p>The guard and the business effect must commit together. Marking an event
 * processed in its own transaction would lose the work on a crash in between
 * and never retry it; doing the work first and marking after would repeat the
 * work on a crash in between. Both are the same write, or neither happens.
 */
public interface ProcessedEventGuard {

    /**
     * Claims this event for this consumer.
     *
     * <p>Must be called inside the transaction that performs the work, and the
     * caller must do nothing when it returns false.
     *
     * @param consumer who is processing — not just the service, but the
     *                 handler, because several handlers in one service consume
     *                 the same topic and each must act on the event once
     * @param eventId  the event's own id
     * @return true on the first delivery, false if this consumer has already
     *         handled it
     */
    boolean claim(String consumer, String eventId);
}
