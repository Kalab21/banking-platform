package com.bankingplatform.common.kafka.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The processed-event guard, as a row in the consumer's own database.
 *
 * <p>The claim is a single insert against a unique key. That is deliberate:
 * a read-then-write — "have I seen this? no, record it" — is two statements
 * with a gap between them, and two concurrent deliveries of the same event
 * both read "no" before either writes. The database is the only thing that
 * can decide this once, so the decision is left to a unique constraint.
 *
 * <p>{@code ON CONFLICT DO NOTHING} turns the second insert into zero rows
 * rather than an exception, so a duplicate is a normal answer rather than a
 * failure to catch. Under concurrency the second insert blocks until the
 * first transaction resolves, then sees the conflict — which is exactly the
 * serialisation wanted.
 *
 * <p><b>Propagation is MANDATORY on purpose.</b> This has to join the caller's
 * transaction, never start its own. In a transaction of its own the claim
 * would commit while the business work was still uncommitted, and a crash in
 * between would leave the event marked processed and the work undone — which
 * is worse than the duplicate it was meant to prevent, because nothing would
 * ever retry it. Failing loudly when there is no surrounding transaction is
 * better than silently providing that guarantee-shaped hole.
 */
public class JdbcProcessedEventGuard implements ProcessedEventGuard {

    private static final String CLAIM = """
            INSERT INTO processed_event (consumer_name, event_id, processed_at)
            VALUES (?, ?, ?)
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public JdbcProcessedEventGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String consumer, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // An event with no id cannot be de-duplicated. Processing it is
            // the lesser evil: refusing would drop a legitimate event because
            // of a producer's omission, and the alternative — treating it as
            // already seen — would drop it silently every time.
            return true;
        }
        return jdbc.update(CLAIM, consumer, eventId, Timestamp.from(Instant.now())) == 1;
    }
}
