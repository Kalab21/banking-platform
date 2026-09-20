package com.bankingplatform.loan.kafka.consumer;

import com.bankingplatform.common.kafka.inbox.ProcessedEventRetention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruning of the processed-event guard, against real PostgreSQL.
 *
 * <p>The prune is written in PostgreSQL's own dialect — it bounds a delete by
 * {@code ctid}, because the table has no surrogate key and PostgreSQL will not
 * take {@code LIMIT} on a {@code DELETE}. A mocked {@code JdbcTemplate} would
 * assert that a string was passed to {@code update}; only the database can say
 * the statement is valid and removes what it claims to.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Processed-event retention — PostgreSQL integration")
class ProcessedEventRetentionIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("loan_db")
            .withUsername("bankingadmin")
            .withPassword("bankingpass");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearClaims() {
        jdbc.update("DELETE FROM processed_event");
    }

    private void claim(String eventId, Duration age) {
        jdbc.update("INSERT INTO processed_event (consumer_name, event_id, processed_at) VALUES (?, ?, ?)",
                "retention-test", eventId, Timestamp.from(Instant.now().minus(age)));
    }

    private List<String> remainingEventIds() {
        return jdbc.queryForList("SELECT event_id FROM processed_event ORDER BY event_id", String.class);
    }

    @Test
    @DisplayName("removes claims past the retention period and keeps the rest")
    void prunesOnlyExpiredClaims() {
        claim("old", Duration.ofDays(40));
        claim("borderline", Duration.ofDays(29));
        claim("fresh", Duration.ofMinutes(5));

        int removed = new ProcessedEventRetention(jdbc, Duration.ofDays(30), 1000).pruneOnce();

        assertThat(removed).isEqualTo(1);
        assertThat(remainingEventIds())
                .as("a claim inside the window still guards a replayable event")
                .containsExactly("borderline", "fresh");
    }

    @Test
    @DisplayName("keeps deleting past the first batch")
    void prunesBeyondOneBatch() {
        for (int i = 0; i < 7; i++) {
            claim("expired-" + i, Duration.ofDays(60));
        }
        claim("kept", Duration.ofHours(1));

        // Batches of two: a single pass would leave five rows behind, so this
        // fails unless the loop actually repeats until a short batch.
        int removed = new ProcessedEventRetention(jdbc, Duration.ofDays(30), 2).pruneOnce();

        assertThat(removed).isEqualTo(7);
        assertThat(remainingEventIds()).containsExactly("kept");
    }

    @Test
    @DisplayName("an empty table prunes to nothing rather than looping")
    void emptyTableIsNoWork() {
        assertThat(new ProcessedEventRetention(jdbc, Duration.ofDays(30), 2).pruneOnce()).isZero();
    }

    @Test
    @DisplayName("a retention shorter than broker retention is refused at construction")
    void refusesRetentionThatWouldOutrunTheBroker() {
        // Pruning a claim while the broker can still redeliver the event is
        // the duplicate this mechanism exists to prevent, reintroduced by its
        // own cleanup. It fails at startup rather than at 03:30.
        assertThatThrownBy(() -> new ProcessedEventRetention(jdbc, Duration.ofDays(1), 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    @Test
    @DisplayName("a non-positive batch size is refused, rather than looping forever")
    void refusesEmptyBatches() {
        // A batch of zero deletes nothing, and nothing equals the batch size,
        // so the loop would exit — but the job would silently never prune.
        assertThatThrownBy(() -> new ProcessedEventRetention(jdbc, Duration.ofDays(30), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
