package com.bankingplatform.common.kafka.outbox;

import com.bankingplatform.common.events.AccountCreated;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The outbox against real PostgreSQL.
 *
 * <p>Both of its controls are the database's: the publisher relies on a unique
 * constraint to collapse a retried publication, and the relay relies on a
 * transaction-scoped advisory lock to keep two replicas off one key. Neither
 * can be shown with a mocked {@code JdbcTemplate} — that would only assert
 * that a string was passed to {@code update}.
 *
 * <p>Kafka itself is mocked here. What is under test is which rows are sent,
 * in what order, and what happens to the table when a send fails; the broker
 * round trip is covered by the embedded-Kafka tests elsewhere in this module.
 */
@Testcontainers
@DisplayName("Transactional outbox — PostgreSQL integration")
class OutboxIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactions;
    private static TransactionTemplate inTransaction;

    // The same mapper the auto-configuration uses, and the same one the
    // JsonSerializer it replaces used, so the payload here is the payload
    // production writes.
    private final ObjectMapper objectMapper = JacksonUtils.enhancedObjectMapper();

    @BeforeAll
    static void schema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        inTransaction = new TransactionTemplate(transactions);

        // The same shape the services' migrations create.
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
                    CONSTRAINT ux_outbox_event_event_id UNIQUE (event_id)
                )
                """);
    }

    @BeforeEach
    void empty() {
        jdbc.update("DELETE FROM outbox_event");
    }

    private OutboxPublisher publisher() {
        // Proxied, so @Transactional(MANDATORY) is actually applied. A
        // hand-built instance carries no proxy and would not exercise it.
        ProxyFactory factory = new ProxyFactory(new JdbcOutboxPublisher(jdbc, objectMapper));
        factory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        factory.setInterfaces(OutboxPublisher.class);
        return (OutboxPublisher) factory.getProxy();
    }

    private static DomainEvent accountCreated(long accountId) {
        return AccountCreated.of(accountId, 42L, "CHECKING");
    }

    private long pendingCount() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    @Nested
    @DisplayName("writing")
    class Writing {

        @Test
        @DisplayName("the row commits with the caller's transaction")
        void rowCommitsWithTheWork() {
            OutboxPublisher outbox = publisher();
            inTransaction.executeWithoutResult(status ->
                    outbox.publish(Topics.ACCOUNT_EVENTS, accountCreated(1L)));

            assertThat(pendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the row rolls back with the caller's transaction")
        void rowRollsBackWithTheWork() {
            // The case the outbox exists for, in reverse: an event must not be
            // announced for a change that did not happen. A consumer acting on
            // an account that was rolled back is worse than a lost event,
            // because the damage is downstream and nothing contradicts it.
            OutboxPublisher outbox = publisher();
            assertThatThrownBy(() -> inTransaction.executeWithoutResult(status -> {
                outbox.publish(Topics.ACCOUNT_EVENTS, accountCreated(2L));
                throw new IllegalStateException("the business work failed");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(pendingCount()).isZero();
        }

        @Test
        @DisplayName("publishing outside a transaction is refused")
        void refusesToPublishWithoutATransaction() {
            // Without MANDATORY the row would commit on its own, and the relay
            // would announce something that had not happened yet and might
            // never happen.
            assertThatThrownBy(() -> publisher().publish(Topics.ACCOUNT_EVENTS, accountCreated(3L)))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(pendingCount()).isZero();
        }

        @Test
        @DisplayName("one publication is queued once, however often it is retried")
        void retriedPublicationIsQueuedOnce() {
            // The event id is minted with the event, so a caller replaying its
            // own transaction is re-queueing the same publication rather than
            // making a new one.
            OutboxPublisher outbox = publisher();
            DomainEvent event = accountCreated(4L);
            inTransaction.executeWithoutResult(status ->
                    outbox.publish(Topics.ACCOUNT_EVENTS, event));
            inTransaction.executeWithoutResult(status ->
                    outbox.publish(Topics.ACCOUNT_EVENTS, event));

            assertThat(pendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the stored payload is the event, byte for byte")
        void payloadIsTheEvent() throws Exception {
            OutboxPublisher outbox = publisher();
            DomainEvent event = accountCreated(5L);
            inTransaction.executeWithoutResult(status ->
                    outbox.publish(Topics.ACCOUNT_EVENTS, event));

            String payload = jdbc.queryForObject(
                    "SELECT payload FROM outbox_event", String.class);
            JsonNode parsed = objectMapper.readTree(payload);

            // Serialised at write time, so what a consumer receives is what
            // the transaction committed — not a re-serialisation of an object
            // whose class may have changed since.
            assertThat(parsed.get("eventType").asText()).isEqualTo(event.eventType());
            assertThat(parsed.get("eventId").asText()).isEqualTo(event.eventId());

            // The invariant that matters, stated directly: the bytes stored are
            // the bytes the JsonSerializer would have sent. Publishing moved
            // from the template to the outbox, and that must not have changed
            // what a consumer receives — not the shape of occurredAt, not
            // whether nulls appear, not anything.
            try (JsonSerializer<DomainEvent> serializer =
                         new JsonSerializer<>(JacksonUtils.enhancedObjectMapper())) {
                byte[] asTheTemplateWouldSend =
                        serializer.serialize(Topics.ACCOUNT_EVENTS, event);
                assertThat(payload.getBytes(StandardCharsets.UTF_8))
                        .as("the outbox stores exactly what the serializer would have sent")
                        .isEqualTo(asTheTemplateWouldSend);
            }
        }
    }

    @Nested
    @DisplayName("relaying")
    class Relaying {

        private RecordingKafka kafka;
        private OutboxRelay relay;

        @BeforeEach
        void relayUnderTest() {
            kafka = new RecordingKafka();
            relay = new OutboxRelay(jdbc, kafka.template(), new OutboxProperties(), transactions);
        }

        private void queue(DomainEvent... events) {
            OutboxPublisher outbox = publisher();
            inTransaction.executeWithoutResult(status -> {
                for (DomainEvent event : events) {
                    outbox.publish(Topics.ACCOUNT_EVENTS, event);
                }
            });
        }

        @Test
        @DisplayName("sends what is pending and marks it sent")
        void sendsAndMarks() {
            queue(accountCreated(10L));

            assertThat(relay.sendPending()).isEqualTo(1);
            assertThat(kafka.sent).hasSize(1);
            assertThat(pendingCount()).isZero();
        }

        @Test
        @DisplayName("a second pass sends nothing, so a row is not sent twice")
        void doesNotResendWhatIsMarked() {
            queue(accountCreated(11L));
            relay.sendPending();

            assertThat(relay.sendPending()).isZero();
            assertThat(kafka.sent).hasSize(1);
        }

        @Test
        @DisplayName("events on one key go out in the order they were written")
        void keepsOrderWithinAKey() {
            // One account's three balance changes. Out of order, the last
            // balance a consumer sees is not the current one.
            queue(accountCreated(20L), accountCreated(20L), accountCreated(20L));

            // The order the rows were written in, which is the order the relay
            // has to send them in. Event ids are random, so comparing them to
            // what arrived is a real check rather than a tautology.
            List<String> written = jdbc.queryForList(
                    "SELECT event_id FROM outbox_event ORDER BY id", String.class);

            relay.sendPending();

            assertThat(kafka.sent).extracting(Sent::key).containsExactly("20", "20", "20");
            assertThat(kafka.sent).extracting(OutboxIT::eventIdOf)
                    .as("sent in written order")
                    .containsExactlyElementsOf(written);
        }

        @Test
        @DisplayName("a failed send stops that key and leaves the rest unsent")
        void failureStopsTheKey() {
            queue(accountCreated(30L), accountCreated(30L), accountCreated(30L));
            kafka.failFrom(1);

            int sent = relay.sendPending();

            // The second failed, so the third waits rather than overtaking it.
            assertThat(sent).isEqualTo(1);
            assertThat(pendingCount()).isEqualTo(2);
            Integer attempts = jdbc.queryForObject(
                    "SELECT max(attempts) FROM outbox_event WHERE published_at IS NULL",
                    Integer.class);
            assertThat(attempts).isEqualTo(1);
        }

        @Test
        @DisplayName("one stalled key does not stall the others")
        void failureIsPerKey() {
            queue(accountCreated(40L), accountCreated(41L));
            kafka.failKey("40");

            relay.sendPending();

            Long stuck = jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
            assertThat(stuck).as("only the poisoned key is still waiting").isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT partition_key FROM outbox_event WHERE published_at IS NULL",
                    String.class)).isEqualTo("40");
        }

        @Test
        @DisplayName("the failure reason is recorded, so a stalled key can be diagnosed")
        void recordsTheReason() {
            queue(accountCreated(50L));
            kafka.failKey("50");

            relay.sendPending();

            String error = jdbc.queryForObject(
                    "SELECT last_error FROM outbox_event", String.class);
            assertThat(error).contains("broker refused");
        }

        @Test
        @DisplayName("two relays racing the same key do not both send it")
        void oneKeyIsDrainedByOneRelayAtATime() throws Exception {
            // SKIP LOCKED on rows would not prevent this: it stops two relays
            // taking the same row, not two adjacent rows of one key. The
            // advisory lock is what makes a key single-threaded across
            // replicas, and without it a later event can overtake an earlier.
            queue(accountCreated(60L), accountCreated(60L), accountCreated(60L));

            int relays = 4;
            CyclicBarrier start = new CyclicBarrier(relays);
            List<RecordingKafka> brokers = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(relays);
            List<java.util.concurrent.Callable<Integer>> passes = new ArrayList<>();
            for (int i = 0; i < relays; i++) {
                RecordingKafka broker = new RecordingKafka();
                brokers.add(broker);
                OutboxRelay competitor =
                        new OutboxRelay(jdbc, broker.template(), new OutboxProperties(), transactions);
                passes.add(() -> {
                    start.await(20, TimeUnit.SECONDS);
                    return competitor.sendPending();
                });
            }

            List<java.util.concurrent.Future<Integer>> results = pool.invokeAll(passes);
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            int total = 0;
            for (java.util.concurrent.Future<Integer> result : results) {
                total += result.get();
            }
            int delivered = brokers.stream().mapToInt(b -> b.sent.size()).sum();

            assertThat(total).as("three events, sent once between them").isEqualTo(3);
            assertThat(delivered).as("no event handed to two brokers").isEqualTo(3);
            assertThat(pendingCount()).isZero();
        }
    }

    @Nested
    @DisplayName("a service with no outbox")
    class NoOutboxTable {

        @Test
        @DisplayName("the relay stays idle rather than erroring every second")
        void relayIsIdleWithoutTheTable() {
            // Every service carries this module, but a consume-only service
            // has no outbox_event. Polling a missing table would log a SQL
            // error on every tick and bury the failures worth reading.
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS empty_service");
            JdbcTemplate elsewhere = new JdbcTemplate(jdbc.getDataSource());
            elsewhere.execute("SET search_path TO empty_service");

            OutboxRelay relay = new OutboxRelay(elsewhere, new RecordingKafka().template(),
                    new OutboxProperties(), transactions);

            // No exception, and nothing sent.
            relay.relay();

            elsewhere.execute("SET search_path TO public");
        }

        @Test
        @DisplayName("pruning is skipped rather than failing")
        void pruneIsSkippedWithoutTheTable() {
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS empty_service_2");
            JdbcTemplate elsewhere = new JdbcTemplate(jdbc.getDataSource());
            elsewhere.execute("SET search_path TO empty_service_2");

            new OutboxRetention(elsewhere, Duration.ofDays(7), 10).prune();

            elsewhere.execute("SET search_path TO public");
        }
    }

    @Nested
    @DisplayName("retention")
    class Retention {

        @Test
        @DisplayName("removes sent rows and never unsent ones")
        void prunesOnlyWhatWasSent() {
            jdbc.update("""
                    INSERT INTO outbox_event
                        (event_id, event_type, topic, partition_key, payload, created_at, published_at)
                    VALUES ('sent-old', 'X', 't', 'k', '{}', now() - interval '40 days',
                            now() - interval '40 days')
                    """);
            jdbc.update("""
                    INSERT INTO outbox_event
                        (event_id, event_type, topic, partition_key, payload, created_at, published_at)
                    VALUES ('sent-new', 'X', 't', 'k', '{}', now(), now())
                    """);
            // The row that must survive at any age: old and never sent is not
            // stale, it is a publication the database already promised and
            // something is wrong with.
            jdbc.update("""
                    INSERT INTO outbox_event
                        (event_id, event_type, topic, partition_key, payload, created_at)
                    VALUES ('stuck-old', 'X', 't', 'k', '{}', now() - interval '400 days')
                    """);

            int removed = new OutboxRetention(jdbc, Duration.ofDays(7), 100).pruneOnce();

            assertThat(removed).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT event_id FROM outbox_event ORDER BY event_id",
                    String.class)).containsExactly("sent-new", "stuck-old");
        }

        @Test
        @DisplayName("keeps deleting past the first batch")
        void prunesBeyondOneBatch() {
            for (int i = 0; i < 5; i++) {
                jdbc.update("""
                        INSERT INTO outbox_event
                            (event_id, event_type, topic, partition_key, payload, created_at, published_at)
                        VALUES (?, 'X', 't', 'k', '{}', now() - interval '40 days',
                                now() - interval '40 days')
                        """, "old-" + i);
            }

            assertThat(new OutboxRetention(jdbc, Duration.ofDays(7), 2).pruneOnce()).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class))
                    .isZero();
        }

        @Test
        @DisplayName("a non-positive retention is refused")
        void refusesZeroRetention() {
            assertThatThrownBy(() -> new OutboxRetention(jdbc, Duration.ZERO, 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** What was handed to Kafka. */
    private record Sent(String topic, String key, String payload) {
    }

    private static String eventIdOf(Sent sent) {
        try {
            return JacksonUtils.enhancedObjectMapper().readTree(sent.payload())
                    .get("eventId").asText();
        } catch (Exception e) {
            throw new IllegalStateException("payload was not the event JSON: " + sent.payload(), e);
        }
    }

    /**
     * A stand-in broker that records what it was given and can refuse.
     *
     * <p>Deliberately not an embedded broker: the questions here are which
     * rows the relay picks and what it does to the table when a send fails,
     * and a real broker makes a refusal hard to arrange without also making
     * the test slow and timing-dependent.
     */
    private static final class RecordingKafka {

        private final List<Sent> sent = new ArrayList<>();
        private final AtomicInteger seen = new AtomicInteger();
        private int failFromIndex = Integer.MAX_VALUE;
        private String failingKey;

        void failFrom(int index) {
            this.failFromIndex = index;
        }

        void failKey(String key) {
            this.failingKey = key;
        }

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, byte[]> template() {
            KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
            when(template.send(anyString(), anyString(), any(byte[].class)))
                    .thenAnswer(invocation -> {
                        String topic = invocation.getArgument(0);
                        String key = invocation.getArgument(1);
                        byte[] value = invocation.getArgument(2);
                        int index = seen.getAndIncrement();
                        if (index >= failFromIndex || key.equals(failingKey)) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("broker refused the record"));
                        }
                        synchronized (sent) {
                            sent.add(new Sent(topic, key, new String(value)));
                        }
                        return CompletableFuture.completedFuture((SendResult<String, byte[]>) null);
                    });
            return template;
        }
    }
}
