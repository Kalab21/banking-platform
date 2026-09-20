package com.bankingplatform.common.kafka.outbox;

import com.bankingplatform.common.kafka.TablePresence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends what the outbox holds, in the order it was written.
 *
 * <p>Runs on a schedule rather than on commit. A listener firing after commit
 * would still be a process that can die between the commit and the send, which
 * is the failure the outbox exists to survive; polling is what makes the send
 * recoverable rather than merely likely.
 *
 * <h2>Ordering</h2>
 *
 * <p>Rows are sent in id order within a partition key, and Kafka preserves
 * that on the wire because the client is idempotent by default from 3.0 —
 * {@code enable.idempotence=true}, {@code acks=all} — so in-flight batches
 * cannot be reordered by a retry.
 *
 * <p>Across replicas the danger is two relays holding rows for the same key at
 * once, which would let a later event overtake an earlier one. {@code FOR
 * UPDATE SKIP LOCKED} alone does not prevent that: it stops two relays taking
 * the same <em>row</em>, not two adjacent rows of the same key. So a key is
 * claimed rather than a row — a transaction-scoped advisory lock per key — and
 * a relay that cannot take a key leaves it to whoever holds it.
 *
 * <h2>Failure</h2>
 *
 * <p>A key stops at its first failed send and the rest of that key waits for
 * the next tick. Skipping past a failure would deliver events out of order,
 * which for a balance is worse than delivering them late. Other keys are
 * unaffected, so one poisoned aggregate does not stall the service.
 *
 * <p>A send that succeeds but whose row is not marked — the relay dies in
 * between — is sent again on the next tick. That is deliberate: the outbox is
 * at-least-once, and every consumer claims the event id, which is what makes
 * the duplicate harmless.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * Keys with something waiting, oldest first, so no key is starved by a
     * busier one. {@code hashtext} maps the key onto the bigint the advisory
     * lock takes; a collision costs two unrelated keys some parallelism and
     * nothing else, because the lock only ever serialises work.
     */
    private static final String CLAIM_KEYS = """
            SELECT partition_key
            FROM outbox_event
            WHERE published_at IS NULL
            GROUP BY partition_key
            HAVING pg_try_advisory_xact_lock(hashtext(partition_key))
            ORDER BY min(id)
            LIMIT ?
            """;

    private static final String PENDING_FOR_KEY = """
            SELECT id, topic, partition_key, payload, attempts
            FROM outbox_event
            WHERE published_at IS NULL AND partition_key = ?
            ORDER BY id
            LIMIT ?
            """;

    private static final String MARK_SENT =
            "UPDATE outbox_event SET published_at = ? WHERE id = ?";

    private static final String MARK_FAILED =
            "UPDATE outbox_event SET attempts = attempts + 1, last_error = ? WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final OutboxProperties properties;
    private final TransactionTemplate transaction;
    private final TablePresence outboxTable;
    private boolean announcedAbsence;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka,
                       OutboxProperties properties, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
        this.outboxTable = new TablePresence(jdbc, "outbox_event");
    }

    @Scheduled(fixedDelayString = "${kafka.outbox.poll-interval:1000}")
    public void relay() {
        // A service that only consumes has this module on its classpath and no
        // outbox. Polling a table that is not there would log a SQL error
        // every second and bury the failures worth reading.
        if (!outboxTable.exists()) {
            if (!announcedAbsence) {
                log.info("No outbox_event table in this service; the relay stays idle");
                announcedAbsence = true;
            }
            return;
        }
        try {
            int sent = sendPending();
            if (sent > 0) {
                log.debug("Relayed {} outbox events", sent);
            }
        } catch (DataAccessException e) {
            // A database blip is not worth killing the scheduler over. The
            // next tick resumes exactly where this left off, because nothing
            // was marked sent.
            log.warn("Outbox relay could not read the outbox: {}", e.getMessage());
        }
    }

    /**
     * One pass: claim some keys, drain each in order.
     *
     * <p>The transaction is opened explicitly rather than with
     * {@code @Transactional}. The annotation would be on a method this class
     * calls on itself, which does not go through the proxy — and the advisory
     * lock is <em>transaction-scoped</em>, so with no transaction to hold it
     * the lock would be released the instant the statement that took it
     * finished, and the ordering guarantee above would quietly not exist.
     *
     * <p>This does hold a database transaction open across a network call to
     * Kafka, which is normally a good way to starve a connection pool. It is
     * the point here — the lock has to outlive the send — so the exposure is
     * bounded instead: a few keys per tick, a bounded batch per key, and a
     * send timeout that cannot exceed
     * {@link OutboxProperties#getSendTimeout()}.
     *
     * @return how many events were published
     */
    public int sendPending() {
        Integer sent = transaction.execute(status -> {
            List<String> keys = jdbc.queryForList(
                    CLAIM_KEYS, String.class, properties.getKeysPerPoll());
            int published = 0;
            for (String key : keys) {
                published += drain(key);
            }
            return published;
        });
        return sent == null ? 0 : sent;
    }

    private int drain(String key) {
        List<PendingEvent> pending = jdbc.query(PENDING_FOR_KEY,
                (rs, row) -> new PendingEvent(
                        rs.getLong("id"), rs.getString("topic"), rs.getString("partition_key"),
                        rs.getString("payload"), rs.getInt("attempts")),
                key, properties.getBatchSize());

        // Issued in id order on one producer, so the broker sees them in that
        // order. Results are collected afterwards rather than blocking on each
        // send, which would make a batch as slow as its round trips.
        List<CompletableFuture<?>> inFlight = new ArrayList<>(pending.size());
        for (PendingEvent event : pending) {
            inFlight.add(kafka.send(event.topic(), event.partitionKey(),
                    event.payload().getBytes(StandardCharsets.UTF_8)));
        }

        Duration timeout = properties.getSendTimeout();
        int sent = 0;
        for (int i = 0; i < pending.size(); i++) {
            PendingEvent event = pending.get(i);
            try {
                inFlight.get(i).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                jdbc.update(MARK_SENT, Timestamp.from(Instant.now()), event.id());
                sent++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(event, "interrupted");
                break;
            } catch (Exception e) {
                recordFailure(event, e.getMessage());
                // Everything after this on the same key waits. Sending it now
                // would put a later event on the topic ahead of an earlier one.
                break;
            }
        }
        return sent;
    }

    private void recordFailure(PendingEvent event, String reason) {
        String message = reason == null ? "unknown" : reason;
        jdbc.update(MARK_FAILED, truncate(message), event.id());

        int attempts = event.attempts() + 1;
        if (attempts >= properties.getWarnAfterAttempts()) {
            // Loud, because this key is now stalled: nothing published for
            // this aggregate will move until this row does.
            log.error("Outbox event {} on {} has failed {} times and is blocking key {}: {}",
                    event.id(), event.topic(), attempts, event.partitionKey(), message);
        } else {
            log.warn("Outbox event {} on {} not sent, will retry: {}",
                    event.id(), event.topic(), message);
        }
    }

    private static String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record PendingEvent(long id, String topic, String partitionKey, String payload,
                                int attempts) {
    }
}
