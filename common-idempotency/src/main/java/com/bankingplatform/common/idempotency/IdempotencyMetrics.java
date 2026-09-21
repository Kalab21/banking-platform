package com.bankingplatform.common.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reports the idempotency records that a person has to resolve.
 *
 * <p>{@link IdempotencyStatus#UNKNOWN} is the status that matters. It means a
 * money movement was attempted, the downstream call was made, and whether it
 * applied was never established — so the key is spent deliberately and the
 * row is left for reconciliation. Exactly one log line is written when that
 * happens, and then nothing ever mentions it again.
 *
 * <p>{@code IN_PROGRESS} rows older than a few minutes are the other half. A
 * claim is written before the operation runs, so a process that dies in
 * between leaves one behind; that row blocks its key forever and is invisible
 * without this.
 *
 * <p>Gauges, because both are states rather than events, and a gauge read
 * from the table at scrape time cannot drift from the table.
 */
public class IdempotencyMetrics {

    private static final String UNKNOWN_OUTCOMES = """
            SELECT count(*) FROM idempotency_record WHERE status = 'UNKNOWN'
            """;

    private static final String ABANDONED_CLAIMS = """
            SELECT count(*) FROM idempotency_record
            WHERE status = 'IN_PROGRESS' AND created_at < now() - interval '15 minutes'
            """;

    private final JdbcTemplate jdbc;
    private volatile Boolean tablePresent;

    public IdempotencyMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;

        registry.gauge("banking.idempotency.unknown", Tags.empty(), this,
                metrics -> metrics.count(UNKNOWN_OUTCOMES));
        registry.gauge("banking.idempotency.abandoned", Tags.empty(), this,
                metrics -> metrics.count(ABANDONED_CLAIMS));
    }

    /**
     * Reads one number, and answers zero rather than throwing.
     *
     * <p>A metric that fails a scrape takes the whole scrape with it, so a
     * service without this table reports nothing interesting rather than
     * breaking the endpoint carrying every other metric.
     */
    private double count(String sql) {
        if (!tableExists()) {
            return 0;
        }
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? 0 : value;
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    private boolean tableExists() {
        Boolean cached = tablePresent;
        if (cached != null) {
            return cached;
        }
        Boolean found = jdbc.queryForObject(
                "SELECT to_regclass('idempotency_record') IS NOT NULL", Boolean.class);
        boolean answer = Boolean.TRUE.equals(found);
        tablePresent = answer;
        return answer;
    }
}
