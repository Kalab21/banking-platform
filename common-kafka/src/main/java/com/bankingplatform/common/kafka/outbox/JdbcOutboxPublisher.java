package com.bankingplatform.common.kafka.outbox;

import com.bankingplatform.common.events.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * The outbox row, written in the caller's transaction.
 *
 * <p><b>Propagation is MANDATORY.</b> In a transaction of its own the row
 * would commit whether or not the business change did, and the relay would
 * then announce something that never happened — an account created that does
 * not exist, an application approved that was rolled back. That is a worse
 * failure than the lost event this replaces, because consumers would act on
 * it. The same reasoning as the processed-event guard, in the other direction.
 *
 * <p>The payload is serialised here rather than at send time so what is stored
 * is exactly what goes on the wire. Serialising in the relay would mean a
 * change to the event class between the write and the send silently altered
 * an already-committed publication.
 *
 * <p>A serialisation failure is deliberately allowed to fail the caller's
 * transaction. An event that cannot be written is one nobody can ever consume,
 * and committing the business change without it is the split-brain the outbox
 * exists to prevent.
 */
public class JdbcOutboxPublisher implements OutboxPublisher {

    private static final String INSERT = """
            INSERT INTO outbox_event
                (event_id, event_type, topic, partition_key, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcOutboxPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, DomainEvent event) {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Event " + event.eventType() + " could not be serialised for the outbox", e);
        }

        // ON CONFLICT on event_id: a caller that retries its own transaction
        // must not queue the same publication twice. The id is minted with the
        // event, so this is the same publication rather than a new one.
        jdbc.update(INSERT,
                event.eventId(),
                event.eventType(),
                topic,
                event.partitionKey(),
                new String(payload, StandardCharsets.UTF_8),
                Timestamp.from(Instant.now()));
    }
}
