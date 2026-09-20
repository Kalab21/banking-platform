package com.bankingplatform.common.kafka.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Prunes claims old enough that no broker still holds the event.
 *
 * <p>The guard table grows with every event the service consumes and nothing
 * removes a row, so left alone it is an unbounded table on the hot path of
 * every consumer — the claim is an insert against its primary key, and that
 * index only gets deeper.
 *
 * <p><b>A claim may only be dropped once the event it guards can no longer be
 * delivered.</b> Redelivery comes from the broker, so the bound is the topic's
 * retention: prune faster than that and a record still sitting in Kafka finds
 * no claim on replay and is processed a second time — the exact duplicate this
 * whole mechanism exists to prevent, reintroduced by the cleanup for it. So
 * the retention is floored at {@link #MINIMUM}, comfortably beyond the
 * platform's topic retention, and a shorter setting is refused at startup
 * rather than quietly honoured.
 *
 * <p>Deletion is batched. One unbounded {@code DELETE} over a table that has
 * been accumulating takes a long-lived lock on rows that live consumers are
 * inserting next to; a bounded batch, repeated, keeps each statement short.
 */
public class ProcessedEventRetention {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventRetention.class);

    /** Below this, pruning would outrun broker retention. See the class note. */
    public static final Duration MINIMUM = Duration.ofDays(7);

    // ctid is PostgreSQL's physical row identifier. The table has no surrogate
    // key to bound a delete by, and PostgreSQL does not accept LIMIT directly
    // on DELETE, so the batch is selected first and deleted by identity.
    private static final String PRUNE = """
            DELETE FROM processed_event
            WHERE ctid IN (
                SELECT ctid FROM processed_event WHERE processed_at < ? LIMIT ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final int batchSize;

    public ProcessedEventRetention(JdbcTemplate jdbc, Duration retention, int batchSize) {
        if (retention == null || retention.compareTo(MINIMUM) < 0) {
            throw new IllegalArgumentException(
                    "kafka.inbox.retention.period must be at least " + MINIMUM
                            + " so claims outlive the events they guard, but was " + retention);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "kafka.inbox.retention.batch-size must be positive, but was " + batchSize);
        }
        this.jdbc = jdbc;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${kafka.inbox.retention.cron:0 30 3 * * *}")
    public void prune() {
        int removed = pruneOnce();
        if (removed > 0) {
            log.info("Pruned {} processed-event claims older than {}", removed, retention);
        }
    }

    /**
     * Deletes every expired claim, a batch at a time.
     *
     * @return how many rows were removed
     */
    public int pruneOnce() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retention));
        int total = 0;
        int removed;
        do {
            removed = jdbc.update(PRUNE, cutoff, batchSize);
            total += removed;
        } while (removed == batchSize);
        return total;
    }
}
