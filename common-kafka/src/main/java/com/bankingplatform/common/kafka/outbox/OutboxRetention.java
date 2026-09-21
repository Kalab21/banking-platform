package com.bankingplatform.common.kafka.outbox;

import com.bankingplatform.common.kafka.TablePresence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Removes rows the relay has already sent.
 *
 * <p>The outbox is a queue, not a journal: without this it accumulates every
 * event the service has ever published, and the relay's own scan for unsent
 * work gets slower for it.
 *
 * <p><b>Only sent rows are eligible.</b> The predicate is
 * {@code published_at IS NOT NULL}, never an age on {@code created_at} — an
 * old row that has not been sent is the one case that must never be deleted,
 * because deleting it silently drops a publication the database has already
 * promised. A row stuck long enough to look prunable is precisely a row
 * something is wrong with.
 */
public class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

    // Bounded by ctid for the same reason as the processed-event prune: no
    // surrogate key to page by, and PostgreSQL takes no LIMIT on DELETE.
    private static final String PRUNE = """
            DELETE FROM outbox_event
            WHERE ctid IN (
                SELECT ctid FROM outbox_event
                WHERE published_at IS NOT NULL AND published_at < ?
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final int batchSize;
    private final TablePresence outboxTable;

    public OutboxRetention(JdbcTemplate jdbc, Duration retention, int batchSize) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException(
                    "kafka.outbox.retention must be positive, but was " + retention);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "kafka.outbox.batch-size must be positive, but was " + batchSize);
        }
        this.jdbc = jdbc;
        this.retention = retention;
        this.batchSize = batchSize;
        this.outboxTable = new TablePresence(jdbc, "outbox_event");
    }

    @Scheduled(cron = "${kafka.outbox.prune-cron:0 45 3 * * *}")
    public void prune() {
        // A consume-only service has this module but no outbox.
        if (!outboxTable.exists()) {
            return;
        }
        int removed = pruneOnce();
        if (removed > 0) {
            log.info("Pruned {} sent outbox rows older than {}", removed, retention);
        }
    }

    /** @return how many sent rows were removed */
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
