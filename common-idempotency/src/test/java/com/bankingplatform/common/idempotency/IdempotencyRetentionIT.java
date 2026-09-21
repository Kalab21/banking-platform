package com.bankingplatform.common.idempotency;

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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruning idempotency records, and reporting the ones that cannot be pruned.
 *
 * <p>The table grows with every money movement on the platform, and the claim
 * is an insert against its unique key on the hot path of all of them. What
 * matters more than the pruning is what the pruning refuses to touch: an
 * {@code UNKNOWN} row is the only evidence that a movement's effect was never
 * established, and deleting it destroys the reason anyone would look.
 */
@Testcontainers
@DisplayName("Idempotency retention and signals — PostgreSQL integration")
class IdempotencyRetentionIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;

    private MeterRegistry registry;

    @BeforeAll
    static void schema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS idempotency_record (
                    id               BIGSERIAL PRIMARY KEY,
                    idempotency_key  VARCHAR(255) NOT NULL,
                    operation        VARCHAR(20)  NOT NULL,
                    request_hash     VARCHAR(64)  NOT NULL,
                    status           VARCHAR(20)  NOT NULL,
                    response_status  INTEGER,
                    response_body    TEXT,
                    result_ref       VARCHAR(80),
                    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
                    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_idempotency_retention_key UNIQUE (idempotency_key)
                )
                """);
    }

    @BeforeEach
    void empty() {
        jdbc.update("DELETE FROM idempotency_record");
        registry = new SimpleMeterRegistry();
        new IdempotencyMetrics(jdbc, registry);
    }

    private void record(String key, String status, String age) {
        jdbc.update("""
                INSERT INTO idempotency_record
                    (idempotency_key, operation, request_hash, status, created_at, updated_at)
                VALUES (?, 'TRANSFER', 'h', ?, now() - (? ::interval), now() - (? ::interval))
                """, key, status, age, age);
    }

    private List<String> remaining() {
        return jdbc.queryForList(
                "SELECT idempotency_key FROM idempotency_record ORDER BY idempotency_key",
                String.class);
    }

    @Test
    @DisplayName("settled records are pruned once no client could still be retrying")
    void settledRecordsArePruned() {
        record("done-old", "COMPLETED", "60 days");
        record("failed-old", "FAILED", "60 days");
        record("done-recent", "COMPLETED", "1 day");

        int removed = new IdempotencyRetention(jdbc, Duration.ofDays(30), 100).pruneOnce();

        assertThat(removed).isEqualTo(2);
        assertThat(remaining()).containsExactly("done-recent");
    }

    @Test
    @DisplayName("an unknown outcome is never pruned, at any age")
    void unknownOutcomesSurviveForever() {
        // The row is the only record that a movement was attempted and its
        // effect on a balance never established. An old one is not stale, it
        // is overdue -- and deleting it destroys the reason anyone would look.
        record("unknown-ancient", "UNKNOWN", "400 days");

        assertThat(new IdempotencyRetention(jdbc, Duration.ofDays(30), 100).pruneOnce()).isZero();
        assertThat(remaining()).containsExactly("unknown-ancient");
    }

    @Test
    @DisplayName("a claim still in progress is never pruned either")
    void claimsInProgressSurvive() {
        // Deleting a claimed key releases it, and the operation it claimed
        // may have already moved money.
        record("claimed", "IN_PROGRESS", "400 days");

        assertThat(new IdempotencyRetention(jdbc, Duration.ofDays(30), 100).pruneOnce()).isZero();
        assertThat(remaining()).containsExactly("claimed");
    }

    @Test
    @DisplayName("keeps deleting past the first batch")
    void prunesBeyondOneBatch() {
        for (int i = 0; i < 7; i++) {
            record("old-" + i, "COMPLETED", "60 days");
        }

        assertThat(new IdempotencyRetention(jdbc, Duration.ofDays(30), 2).pruneOnce()).isEqualTo(7);
    }

    @Test
    @DisplayName("a retention shorter than a day is refused at startup")
    void tooShortARetentionIsRefused() {
        // Below this a client could still be retrying a key the platform has
        // forgotten, and the retry would move money a second time.
        assertThatThrownBy(() -> new IdempotencyRetention(jdbc, Duration.ofHours(1), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    @Test
    @DisplayName("unresolved outcomes are reported, because nothing else will mention them again")
    void unknownOutcomesAreReported() {
        record("unknown-1", "UNKNOWN", "2 days");
        record("unknown-2", "UNKNOWN", "1 day");
        record("done", "COMPLETED", "1 day");

        assertThat(registry.get("banking.idempotency.unknown").gauge().value()).isEqualTo(2);
    }

    @Test
    @DisplayName("claims abandoned by a dead process are reported separately")
    void abandonedClaimsAreReported() {
        // A claim is written before the operation runs, so a process that
        // dies in between leaves one behind. That row blocks its key forever
        // and is invisible without this.
        record("abandoned", "IN_PROGRESS", "2 hours");
        record("running", "IN_PROGRESS", "1 minute");

        assertThat(registry.get("banking.idempotency.abandoned").gauge().value()).isEqualTo(1);
    }
}
