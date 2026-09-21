package com.bankingplatform.common.kafka.outbox;

import com.bankingplatform.common.kafka.TablePresence;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Makes the outbox's failure states visible to something other than a log
 * line nobody is reading.
 *
 * <p>The outbox turns a lost event into a delayed one, which is the right
 * trade and also a quieter one: an event that is never sent now sits in a
 * table rather than vanishing, and the platform carries on looking healthy.
 * A stalled key blocks its own aggregate indefinitely and produces one error
 * line per attempt — true, and invisible on any dashboard.
 *
 * <p>Three numbers, because three different things go wrong:
 *
 * <ul>
 *   <li><b>pending</b> — how much is waiting. Normal is close to zero and
 *       briefly non-zero; a number that only grows means the relay is not
 *       running at all, which has happened on this platform twice.</li>
 *   <li><b>oldest age</b> — how long the oldest unsent event has waited.
 *       This is the one to alert on: a large pending count that drains is
 *       load, a single row that has waited an hour is a stall.</li>
 *   <li><b>failing</b> — rows that have been attempted and refused. Distinct
 *       from pending, because a backlog of never-attempted rows and a row the
 *       broker keeps rejecting need different responses.</li>
 * </ul>
 *
 * <p>Gauges rather than counters: these are states, not events, and a gauge
 * that reads the table at scrape time cannot drift from it the way a counter
 * maintained in memory can.
 */
public class OutboxMetrics {

    private static final String PENDING = """
            SELECT count(*) FROM outbox_event WHERE published_at IS NULL
            """;

    private static final String OLDEST_AGE_SECONDS = """
            SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
            FROM outbox_event WHERE published_at IS NULL
            """;

    private static final String FAILING = """
            SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND attempts > 0
            """;

    private final JdbcTemplate jdbc;
    private final TablePresence outboxTable;

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.outboxTable = new TablePresence(jdbc, "outbox_event");

        registry.gauge("banking.outbox.pending", Tags.empty(), this,
                metrics -> metrics.count(PENDING));
        registry.gauge("banking.outbox.oldest.age.seconds", Tags.empty(), this,
                metrics -> metrics.count(OLDEST_AGE_SECONDS));
        registry.gauge("banking.outbox.failing", Tags.empty(), this,
                metrics -> metrics.count(FAILING));
    }

    /**
     * Reads one number, and answers zero rather than throwing.
     *
     * <p>A metric that fails a scrape takes the rest of the scrape with it. A
     * service without an outbox table reports nothing interesting rather than
     * breaking the endpoint that carries every other metric.
     */
    private double count(String sql) {
        if (!outboxTable.exists()) {
            return 0;
        }
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? 0 : value;
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }
}
