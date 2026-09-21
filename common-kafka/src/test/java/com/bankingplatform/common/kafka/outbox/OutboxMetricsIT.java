package com.bankingplatform.common.kafka.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox reporting its own backlog.
 *
 * <p>Against real PostgreSQL, because a gauge that reports the wrong number
 * is worse than no gauge: it is an alert that does not fire. What is under
 * test is that each one reads the state it claims to, and that none of them
 * can break a scrape.
 */
@Testcontainers
@DisplayName("Outbox metrics — PostgreSQL integration")
class OutboxMetricsIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static DataSource dataSource;

    private MeterRegistry registry;

    @BeforeAll
    static void schema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        dataSource = source;
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS outbox_event (
                    id            BIGSERIAL    PRIMARY KEY,
                    event_id      VARCHAR(64)  NOT NULL,
                    event_type    VARCHAR(80)  NOT NULL,
                    topic         VARCHAR(120) NOT NULL,
                    partition_key VARCHAR(120) NOT NULL,
                    payload       TEXT         NOT NULL,
                    created_at    TIMESTAMP    NOT NULL,
                    published_at  TIMESTAMP,
                    attempts      INT          NOT NULL DEFAULT 0,
                    last_error    VARCHAR(500),
                    CONSTRAINT ux_outbox_metrics_event_id UNIQUE (event_id)
                )
                """);
    }

    @BeforeEach
    void empty() {
        jdbc.update("DELETE FROM outbox_event");
        registry = new SimpleMeterRegistry();
        new OutboxMetrics(jdbc, registry);
    }

    private void row(String id, String createdAt, boolean sent, int attempts) {
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, event_type, topic, partition_key, payload, created_at,
                     published_at, attempts)
                VALUES (?, 'X', 't', 'k', '{}', now() - (? ::interval), ?, ?)
                """, id, createdAt, sent ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
                attempts);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("pending counts what is waiting, and ignores what was sent")
    void pendingCountsTheBacklog() {
        row("a", "1 minute", false, 0);
        row("b", "2 minutes", false, 0);
        row("c", "3 minutes", true, 0);

        assertThat(gauge("banking.outbox.pending")).isEqualTo(2);
    }

    @Test
    @DisplayName("the oldest age is the number that distinguishes a stall from load")
    void oldestAgeIsReported() {
        // A large backlog that drains is load. A single row that has waited an
        // hour is a relay that is not running, and only this number says so.
        row("old", "90 minutes", false, 0);
        row("new", "1 minute", false, 0);

        assertThat(gauge("banking.outbox.oldest.age.seconds"))
                .isGreaterThan(5000)
                .isLessThan(6000);
    }

    @Test
    @DisplayName("failing counts rows the broker has already refused")
    void failingIsSeparateFromPending() {
        // A backlog of never-attempted rows and a row the broker keeps
        // rejecting need different responses, so they are different numbers.
        row("untried", "1 minute", false, 0);
        row("refused", "1 minute", false, 3);

        assertThat(gauge("banking.outbox.pending")).isEqualTo(2);
        assertThat(gauge("banking.outbox.failing")).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty outbox reports zero rather than nothing")
    void emptyIsZero() {
        assertThat(gauge("banking.outbox.pending")).isZero();
        assertThat(gauge("banking.outbox.oldest.age.seconds")).isZero();
    }

    @Test
    @DisplayName("a service with no outbox table reports zero rather than failing the scrape")
    void aMissingTableDoesNotBreakTheScrape() {
        // Every service carries this module; a consume-only one has no
        // outbox. A metric that throws takes the whole /actuator/prometheus
        // response with it, so this must degrade rather than fail.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS no_outbox");
        JdbcTemplate elsewhere = new JdbcTemplate(dataSource);
        elsewhere.execute("SET search_path TO no_outbox");

        MeterRegistry isolated = new SimpleMeterRegistry();
        new OutboxMetrics(elsewhere, isolated);

        assertThat(isolated.get("banking.outbox.pending").gauge().value()).isZero();
        elsewhere.execute("SET search_path TO public");
    }
}
