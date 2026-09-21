package com.bankingplatform.payment.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reports scheduled payments that are not moving.
 *
 * <p>A claim is durable on purpose: it survives the worker that made it, so
 * a worker that dies leaves its payment in PROCESSING. Recovery re-claims
 * those after a grace period, which means a payment stuck for hours is not a
 * dead worker but a payment that keeps failing — and the only trace of it is
 * one error line per attempt.
 *
 * <p>{@code overdue} is the other side: payments that are due and have not
 * been claimed at all. A number that grows means the scheduler is not
 * running, which looks exactly like a quiet day.
 */
@Component
public class PaymentMetrics {

    private static final String STUCK_PROCESSING = """
            SELECT count(*) FROM payments
            WHERE status = 'PROCESSING' AND updated_at < now() - interval '1 hour'
            """;

    private static final String OVERDUE = """
            SELECT count(*) FROM payments
            WHERE status = 'PENDING' AND scheduled_at IS NOT NULL
              AND scheduled_at < now() - interval '10 minutes'
            """;

    private final JdbcTemplate jdbc;

    public PaymentMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;

        // Registered with a strong reference on purpose. The shorthand
        // MeterRegistry.gauge(name, tags, obj, fn) keeps a *weak* reference to
        // the object it measures, so the moment nothing else holds that object
        // the gauge reports NaN rather than disappearing -- an alert that
        // silently stops firing, which is the worst way for a metric to fail.
        // It survives here only because Spring holds the bean, which is luck
        // rather than design; a test that built one without keeping it is what
        // exposed it.
        Gauge.builder("banking.payments.stuck", this, metrics -> metrics.count(STUCK_PROCESSING))
                .strongReference(true)
                .register(registry);
        Gauge.builder("banking.payments.overdue", this, metrics -> metrics.count(OVERDUE))
                .strongReference(true)
                .register(registry);
    }

    private double count(String sql) {
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? 0 : value;
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }
}
