package com.bankingplatform.common.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the container actually does with a record that fails.
 *
 * <p>Against a real broker, because every claim here is container behaviour:
 * how many times a listener is called, whether the offset advances, and where
 * the record ends up. A mock would assert the mock.
 *
 * <p>Each case corresponds to a way this platform used to lose events. Before
 * this change there was no error handler at all, so the container's default
 * applied — ten immediate attempts, then commit the offset anyway — and a
 * payload that would not deserialize failed before the listener and pinned the
 * partition permanently.
 *
 * <p>Every scenario owns a topic and its own counters. Sharing either would
 * make the assertions depend on the order JUnit happens to run them in.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "flaky-events", "flaky-events.DLT",
                "poison-events", "poison-events.DLT",
                "resume-events", "resume-events.DLT",
                "garbage-events", "garbage-events.DLT",
                "shape-events", "shape-events.DLT",
        },
        brokerProperties = {"auto.create.topics.enable=true"})
@TestPropertySource(properties = {
        "spring.kafka.consumer.group-id=recovery-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer="
                + "org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
        "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class="
                + "org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.use.type.headers=false",
        "spring.kafka.consumer.properties.spring.json.value.default.type="
                + "com.bankingplatform.common.kafka.KafkaRecoveryIT$TestEvent",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.bankingplatform.common.kafka",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.producer.properties.spring.json.add.type.headers=false",
        // Short waits so the test asserts the policy rather than sitting in it.
        "kafka.recovery.max-retries=3",
        "kafka.recovery.initial-interval=20ms",
        "kafka.recovery.multiplier=1.0",
        "kafka.recovery.max-interval=20ms",
})
@DisplayName("Kafka retry and dead-letter recovery")
class KafkaRecoveryIT {

    /** Shaped like a real event: an envelope Jackson can read without the contract module. */
    public record TestEvent(String eventId, String eventType, String note) {
    }

    @Autowired
    private KafkaTemplate<Object, Object> template;

    @Autowired
    private Listeners listeners;

    @Autowired
    private EmbeddedKafkaBroker broker;

    /** Reads whatever landed on a dead-letter topic, as raw bytes plus headers. */
    private ConsumerRecord<String, byte[]> readDeadLetter(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-reader-" + topic, "false", broker);
        props.put("key.deserializer", StringDeserializer.class);
        props.put("value.deserializer", ByteArrayDeserializer.class);
        try (Consumer<String, byte[]> consumer =
                     new DefaultKafkaConsumerFactory<String, byte[]>(props).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            return KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(25));
        }
    }

    /** Publishes bytes exactly as given, bypassing the JSON serializer. */
    private void sendRaw(String topic, String key, String body) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", ByteArraySerializer.class);
        new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(props))
                .send(new ProducerRecord<>(topic, key, body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String headerValue(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int intHeader(ConsumerRecord<?, ?> record, String name) {
        return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getInt();
    }

    private static long longHeader(ConsumerRecord<?, ?> record, String name) {
        return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getLong();
    }

    @Nested
    @DisplayName("a failure that goes away")
    class Transient {

        @Test
        @DisplayName("is retried, and the business effect happens once")
        void retriedThenSucceeds() throws Exception {
            listeners.flakyFailuresLeft.set(2);

            template.send("flaky-events", "k1", new TestEvent("evt-1", "FLAKY", "hello"));

            String processed = listeners.flakyProcessed.poll(30, TimeUnit.SECONDS);

            assertThat(processed).as("the record is eventually processed").isEqualTo("evt-1");

            // Three deliveries: two that threw and one that worked. The old
            // default gave ten immediate attempts and then dropped the record.
            assertThat(listeners.flakyAttempts).hasValue(3);

            // Retrying is only worth anything if the effect still happens —
            // once — rather than the event being lost on the first failure.
            assertThat(listeners.flakyProcessed).isEmpty();
        }
    }

    @Nested
    @DisplayName("a failure that never goes away")
    class Poison {

        @Test
        @DisplayName("is retried a bounded number of times, then dead-lettered with its context")
        void deadLetteredWithContext() throws Exception {
            template.send("poison-events", "k2", new TestEvent("evt-2", "POISON", "always fails"));

            ConsumerRecord<String, byte[]> dead = readDeadLetter("poison-events.DLT");

            assertThat(dead).as("the record is kept, not dropped").isNotNull();

            // Bounded: four deliveries in total, not ten and not forever.
            assertThat(listeners.poisonAttempts).hasValue(4);

            assertThat(headerValue(dead, "kafka_dlt-original-topic")).isEqualTo("poison-events");
            assertThat(intHeader(dead, "kafka_dlt-original-partition")).isZero();
            assertThat(longHeader(dead, "kafka_dlt-original-offset")).isNotNegative();

            // The thrown exception is the container's wrapper; the listener's
            // own failure is on the cause header. Both are asserted, because
            // an operator reading only the first would see
            // ListenerExecutionFailedException and learn nothing.
            assertThat(headerValue(dead, "kafka_dlt-exception-fqcn"))
                    .contains("ListenerExecutionFailedException");
            assertThat(headerValue(dead, "kafka_dlt-exception-cause-fqcn"))
                    .contains("IllegalStateException");
            assertThat(headerValue(dead, "kafka_dlt-exception-message"))
                    .contains("this one never works");

            // The two things Spring cannot know. Several services consume the
            // same topics and all dead-letter to the same place, so without
            // the group a dead record does not say who failed to handle it.
            assertThat(headerValue(dead, DeadLetterHeaders.CONSUMER_GROUP)).isEqualTo("recovery-test");
            assertThat(headerValue(dead, DeadLetterHeaders.EVENT_ID)).isEqualTo("evt-2");
            assertThat(headerValue(dead, DeadLetterHeaders.EVENT_TYPE)).isEqualTo("POISON");
            assertThat(headerValue(dead, DeadLetterHeaders.ATTEMPTS)).isEqualTo("4");

            // The payload survives, so the record can be understood and replayed.
            assertThat(new String(dead.value(), StandardCharsets.UTF_8)).contains("evt-2");
        }

        @Test
        @DisplayName("does not block the records behind it")
        void partitionKeepsMoving() throws Exception {
            // Both records on one key, so both on one partition: the healthy
            // one is genuinely queued behind the failing one. This scenario
            // publishes both itself rather than depending on another test.
            template.send("resume-events", "same-key", new TestEvent("evt-bad", "POISON", "never works"));
            template.send("resume-events", "same-key", new TestEvent("evt-good", "HEALTHY", "behind it"));

            String processed = listeners.resumeProcessed.poll(60, TimeUnit.SECONDS);

            assertThat(processed)
                    .as("a healthy record behind a dead-lettered one is still processed")
                    .isEqualTo("evt-good");
        }
    }

    @Nested
    @DisplayName("a payload that cannot be read at all")
    class Malformed {

        /**
         * The failure mode that mattered most. Deserialization happens before
         * the listener, so an unwrapped deserializer gives the error handler
         * nothing to recover: the container retries the same offset forever
         * and the partition never advances again.
         */
        @Test
        @DisplayName("is dead-lettered instead of stopping the partition forever")
        void malformedIsDeadLettered() throws Exception {
            sendRaw("garbage-events", "k9", "{ this is not valid json");

            ConsumerRecord<String, byte[]> dead = readDeadLetter("garbage-events.DLT");

            assertThat(dead).as("the unreadable record is kept").isNotNull();
            assertThat(headerValue(dead, "kafka_dlt-exception-fqcn"))
                    .contains("DeserializationException");

            // Never delivered to the listener: it could not succeed, and
            // retrying a poison record is how a partition stops for good.
            assertThat(listeners.garbageAttempts).hasValue(0);

            // The original bytes are preserved rather than re-encoded, so what
            // is on the dead letter topic is what was published.
            assertThat(new String(dead.value(), StandardCharsets.UTF_8))
                    .isEqualTo("{ this is not valid json");
        }

        /**
         * Well-formed JSON can still fail to deserialize — here {@code note}
         * is an object where the type declares a string. The dead-lettered
         * value is then raw bytes, so the event id has to be read out of the
         * JSON rather than off a deserialized object.
         */
        @Test
        @DisplayName("still carries its event id when the payload is readable but the wrong shape")
        void unparseableShapeKeepsItsEventId() throws Exception {
            sendRaw("shape-events", "k8",
                    "{\"eventId\":\"evt-9\",\"eventType\":\"WRONG_SHAPE\",\"note\":{\"nested\":true}}");

            ConsumerRecord<String, byte[]> dead = readDeadLetter("shape-events.DLT");

            assertThat(headerValue(dead, "kafka_dlt-exception-fqcn"))
                    .contains("DeserializationException");
            assertThat(headerValue(dead, DeadLetterHeaders.EVENT_ID)).isEqualTo("evt-9");
            assertThat(headerValue(dead, DeadLetterHeaders.EVENT_TYPE)).isEqualTo("WRONG_SHAPE");
        }
    }

    @SpringBootApplication
    static class TestApp {

        @Bean
        Listeners listeners() {
            return new Listeners();
        }
    }

    /** One listener per scenario, each with its own counters. */
    static class Listeners {

        final AtomicInteger flakyAttempts = new AtomicInteger();
        final AtomicInteger poisonAttempts = new AtomicInteger();
        final AtomicInteger garbageAttempts = new AtomicInteger();
        final AtomicInteger flakyFailuresLeft = new AtomicInteger();

        final BlockingQueue<String> flakyProcessed = new ArrayBlockingQueue<>(8);
        final BlockingQueue<String> resumeProcessed = new ArrayBlockingQueue<>(8);

        @KafkaListener(topics = "flaky-events", groupId = "recovery-test")
        public void onFlaky(TestEvent event) {
            flakyAttempts.incrementAndGet();
            if (flakyFailuresLeft.getAndDecrement() > 0) {
                // Propagated, not caught. Swallowing it would tell the
                // container this record succeeded.
                throw new IllegalStateException("transient failure");
            }
            flakyProcessed.add(event.eventId());
        }

        @KafkaListener(topics = "poison-events", groupId = "recovery-test")
        public void onPoison(TestEvent event) {
            poisonAttempts.incrementAndGet();
            throw new IllegalStateException("this one never works");
        }

        @KafkaListener(topics = "resume-events", groupId = "recovery-test")
        public void onResume(TestEvent event) {
            if ("HEALTHY".equals(event.eventType())) {
                resumeProcessed.add(event.eventId());
                return;
            }
            throw new IllegalStateException("the record in front");
        }

        @KafkaListener(topics = "garbage-events", groupId = "recovery-test")
        public void onGarbage(TestEvent event) {
            garbageAttempts.incrementAndGet();
        }

        @KafkaListener(topics = "shape-events", groupId = "recovery-test")
        public void onShape(TestEvent event) {
            // Never reached: these records fail to deserialize.
        }
    }
}
