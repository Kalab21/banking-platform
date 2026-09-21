package com.bankingplatform.transaction.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reports transfers that did not finish.
 *
 * <p>A half-applied transfer is recorded and logged at error, and then
 * nothing mentions it again. The row is the evidence; this is the number an
 * alert can be hung on, because the whole point of that record is that a
 * person has to act on it and no part of the platform will.
 *
 * <p>Split deliberately. {@code half_applied} is money that has left one
 * account and arrived nowhere — the thing worth waking someone for.
 * {@code unsettled} is the broader set, including transfers whose fate is
 * merely unknown, which matters but is not the same emergency.
 */
@Component
public class TransferMetrics {

    private static final String HALF_APPLIED = """
            SELECT count(*) FROM transfer_attempt
            WHERE status = 'CREDIT_FAILED'
               OR (status = 'RECONCILED' AND debit_applied AND NOT credit_applied)
            """;

    private static final String UNSETTLED = """
            SELECT count(*) FROM transfer_attempt
            WHERE status IN ('STARTED', 'DEBITED', 'CREDIT_FAILED')
              AND created_at < now() - interval '5 minutes'
            """;

    private final JdbcTemplate jdbc;

    public TransferMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;

        registry.gauge("banking.transfers.half.applied", Tags.empty(), this,
                metrics -> metrics.count(HALF_APPLIED));
        registry.gauge("banking.transfers.unsettled", Tags.empty(), this,
                metrics -> metrics.count(UNSETTLED));
    }

    /**
     * Reads one number, and answers zero rather than throwing: a metric that
     * fails a scrape takes every other metric on that endpoint with it.
     */
    private double count(String sql) {
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? 0 : value;
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }
}
