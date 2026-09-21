package com.bankingplatform.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Prunes settled idempotency records.
 *
 * <p>The table grows with every money movement on the platform — four
 * services now — and the claim is an insert against its unique key on the
 * hot path of every one of them. Nothing removed a row, which was owed and
 * noted twice before being done here.
 *
 * <p><b>Only {@code COMPLETED} and {@code FAILED} rows are eligible.</b>
 *
 * <ul>
 *   <li>{@code COMPLETED} is safe to drop once no client could still be
 *       retrying: after that, the key is simply unused again.</li>
 *   <li>{@code FAILED} already means the key was released for retry, so the
 *       row carries no verdict worth keeping.</li>
 *   <li>{@code UNKNOWN} is <b>never</b> pruned at any age. It is the record of
 *       a movement whose effect on a balance was never established, and
 *       deleting it destroys the only evidence that someone has to reconcile
 *       it. An old one is not stale — it is overdue.</li>
 *   <li>{@code IN_PROGRESS} is never pruned either. A row still claimed is
 *       either running or abandoned, and deleting it would release a key that
 *       may have already moved money.</li>
 * </ul>
 *
 * <p>Deletion is batched by {@code ctid} for the same reason as the other
 * pruning on this platform: no surrogate key to page by, and PostgreSQL takes
 * no {@code LIMIT} on a {@code DELETE}.
 */
public class IdempotencyRetention {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetention.class);

    /** Below this, a client could still be retrying a key that has been forgotten. */
    public static final Duration MINIMUM = Duration.ofDays(1);

    private static final String PRUNE = """
            DELETE FROM idempotency_record
            WHERE ctid IN (
                SELECT ctid FROM idempotency_record
                WHERE status IN ('COMPLETED', 'FAILED') AND updated_at < ?
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final int batchSize;

    public IdempotencyRetention(JdbcTemplate jdbc, Duration retention, int batchSize) {
        if (retention == null || retention.compareTo(MINIMUM) < 0) {
            throw new IllegalArgumentException(
                    "idempotency.retention.period must be at least " + MINIMUM
                            + " so a key outlives any client still retrying it, but was " + retention);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "idempotency.retention.batch-size must be positive, but was " + batchSize);
        }
        this.jdbc = jdbc;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${idempotency.retention.cron:0 15 4 * * *}")
    public void prune() {
        if (!tableExists()) {
            return;
        }
        int removed = pruneOnce();
        if (removed > 0) {
            log.info("Pruned {} settled idempotency records older than {}", removed, retention);
        }
    }

    /** @return how many settled rows were removed */
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

    private boolean tableExists() {
        Boolean found = jdbc.queryForObject(
                "SELECT to_regclass('idempotency_record') IS NOT NULL", Boolean.class);
        return Boolean.TRUE.equals(found);
    }
}
