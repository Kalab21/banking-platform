package com.bankingplatform.common.kafka;

/**
 * The extra headers this platform writes onto a dead-lettered record.
 *
 * <p>Spring already records where the record came from and what went wrong:
 * the original topic, partition, offset and timestamp, and the exception's
 * class, message and stack trace. Two things it cannot know are added here.
 *
 * <p>The first is the consumer group. A dead-lettered record is useless
 * without it, because five services consume {@code transaction-events} and
 * they all dead-letter to {@code transaction-events.DLT}. Without the group,
 * a record on that topic says something failed but not who failed to process
 * it — and replaying it to every consumer would repeat the four side effects
 * that did succeed.
 *
 * <p>The second is the event id, copied out of the payload so the record can
 * be matched to the original publication without parsing the body.
 *
 * <p>Nothing sensitive is copied. The events on these topics carry no account
 * number, card number, password or token — that is asserted by a contract test
 * in {@code common-events} — so the payload and the exception detail are safe
 * to keep alongside each other here.
 */
public final class DeadLetterHeaders {

    private DeadLetterHeaders() {
    }

    /** Which consumer group failed to process the record. */
    public static final String CONSUMER_GROUP = "x-dlt-consumer-group";

    /** The {@code eventId} from the payload, when it could be read. */
    public static final String EVENT_ID = "x-dlt-event-id";

    /** The {@code eventType} from the payload, when it could be read. */
    public static final String EVENT_TYPE = "x-dlt-event-type";

    /** How many deliveries were attempted before the record was given up on. */
    public static final String ATTEMPTS = "x-dlt-attempts";
}
