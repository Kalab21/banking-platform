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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SimpleKafkaHeaderMapper;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a producer is allowed to make this JVM do through a record's headers.
 *
 * <p>The payload contract and the header contract are different trust
 * boundaries. Turning off payload type headers and narrowing trusted packages
 * says nothing about header mapping, which Spring performs separately while
 * building the message for a listener. Left at the default, a
 * {@code spring_json_header_types} header names a Java class and the mapper
 * constructs it — from bytes the producer chose.
 *
 * <p>These assert the <em>effective listener configuration</em>: real
 * listeners, a real broker, records published with hand-built headers. A test
 * of {@link SimpleKafkaHeaderMapper} on its own would prove the mapper works
 * and nothing about whether this platform uses it.
 */
// Names its own configuration: two integration tests in this package each
// declare one, and an unqualified @SpringBootTest cannot choose between them.
@SpringBootTest(classes = KafkaHeaderSafetyIT.TestApp.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {"header-events", "header-events.DLT", "hostile-events", "hostile-events.DLT",
                  "malformed-events", "malformed-events.DLT"},
        brokerProperties = {"auto.create.topics.enable=true"})
@TestPropertySource(properties = {
        "spring.kafka.consumer.group-id=header-safety-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer="
                + "org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
        "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class="
                + "org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.use.type.headers=false",
        "spring.kafka.consumer.properties.spring.json.value.default.type="
                + "com.bankingplatform.common.kafka.KafkaHeaderSafetyIT$TestEvent",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.bankingplatform.common.kafka",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.producer.properties.spring.json.add.type.headers=false",
        "kafka.recovery.max-retries=1",
        "kafka.recovery.initial-interval=20ms",
        "kafka.recovery.multiplier=1.0",
        "kafka.recovery.max-interval=20ms",
})
@DisplayName("Kafka header safety")
class KafkaHeaderSafetyIT {

    public record TestEvent(String eventId, String eventType, String note) {
    }

    /** The header Spring's default mapper reads to decide what Java type to build. */
    private static final String JSON_TYPES = "spring_json_header_types";

    @Autowired
    private KafkaTemplate<Object, Object> template;

    @Autowired
    private Listeners listeners;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private RecordMessageConverter converter;

    private void sendWithHeaders(String topic, String key, String json, Map<String, String> headers) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", ByteArraySerializer.class);
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(topic, key, json.getBytes(StandardCharsets.UTF_8));
        headers.forEach((name, value) ->
                record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(props)).send(record);
    }

    private static String event(String id, String type) {
        return "{\"eventId\":\"" + id + "\",\"eventType\":\"" + type + "\",\"note\":\"n\"}";
    }

    @Nested
    @DisplayName("the mapper this platform actually runs")
    class EffectiveConfiguration {

        @Test
        @DisplayName("is the raw one, on the converter Boot gives every listener factory")
        void rawMapperIsInstalled() {
            // Asserted on the bean Boot hands to the container factory, not on
            // a mapper constructed in the test.
            assertThat(converter).isInstanceOf(MessagingMessageConverter.class);
            assertThat(converter).extracting("headerMapper")
                    .isInstanceOf(SimpleKafkaHeaderMapper.class);
        }
    }

    @Nested
    @DisplayName("ordinary traffic")
    class StillWorks {

        @Test
        @DisplayName("a normal event still reaches the listener")
        void normalEventDelivered() throws Exception {
            template.send("header-events", "k1", new TestEvent("evt-1", "NORMAL", "n"));

            assertThat(listeners.delivered.poll(30, TimeUnit.SECONDS)).isEqualTo("evt-1");
        }

        @Test
        @DisplayName("correlation metadata still arrives, as the bytes the producer wrote")
        void correlationHeaderSurvives() throws Exception {
            sendWithHeaders("header-events", "k2", event("evt-2", "NORMAL"),
                    Map.of("X-Request-Id", "req-abc-123"));

            assertThat(listeners.delivered.poll(30, TimeUnit.SECONDS)).isEqualTo("evt-2");

            Object correlation = listeners.lastHeaders.get("X-Request-Id");
            assertThat(asText(correlation)).isEqualTo("req-abc-123");
        }

        @Test
        @DisplayName("headers this platform has no use for are ignored rather than fatal")
        void unknownHeadersAreHarmless() throws Exception {
            sendWithHeaders("header-events", "k3", event("evt-3", "NORMAL"),
                    Map.of("x-something-nobody-reads", "whatever",
                            "another-unknown", "value"));

            assertThat(listeners.delivered.poll(30, TimeUnit.SECONDS)).isEqualTo("evt-3");
        }
    }

    @Nested
    @DisplayName("a producer trying to choose a Java type")
    class Hostile {

        /**
         * The core of CVE-2026-41731. The default mapper reads
         * {@code spring_json_header_types} and builds the named class; here
         * the record asks for {@code java.net.URL}, whose construction alone
         * is enough to show the capability exists.
         */
        @Test
        @DisplayName("cannot make a typed object out of spring_json_header_types")
        void jsonHeaderTypesIsNotHonoured() throws Exception {
            sendWithHeaders("header-events", "k4", event("evt-4", "NORMAL"),
                    Map.of(JSON_TYPES, "{\"attack\":\"java.net.URL\"}",
                            "attack", "\"http://northbank.invalid/payload\""));

            assertThat(listeners.delivered.poll(30, TimeUnit.SECONDS)).isEqualTo("evt-4");

            Object attack = listeners.lastHeaders.get("attack");
            assertThat(attack)
                    .as("the header stays raw instead of becoming the type the producer named")
                    .isNotInstanceOf(java.net.URL.class);
            assertThat(asText(attack)).contains("northbank.invalid");
        }

        @Test
        @DisplayName("cannot name a java.* type and have it constructed")
        void javaTypeHeaderIsNotConstructed() throws Exception {
            sendWithHeaders("header-events", "k5", event("evt-5", "NORMAL"),
                    Map.of(JSON_TYPES, "{\"when\":\"java.util.Date\",\"amount\":\"java.math.BigDecimal\"}",
                            "when", "0",
                            "amount", "1000000"));

            assertThat(listeners.delivered.poll(30, TimeUnit.SECONDS)).isEqualTo("evt-5");

            assertThat(listeners.lastHeaders.get("when")).isNotInstanceOf(java.util.Date.class);
            assertThat(listeners.lastHeaders.get("amount")).isNotInstanceOf(java.math.BigDecimal.class);
        }

        @Test
        @DisplayName("the type header itself is just bytes, not an instruction")
        void theTypeHeaderIsItselfRaw() throws Exception {
            sendWithHeaders("header-events", "k6", event("evt-6", "NORMAL"),
                    Map.of(JSON_TYPES, "{\"x\":\"java.lang.String\"}", "x", "\"plain\""));

            assertThat(listeners.delivered.poll(30, TimeUnit.SECONDS)).isEqualTo("evt-6");

            // Present, carried through, and inert.
            assertThat(asText(listeners.lastHeaders.get(JSON_TYPES))).contains("java.lang.String");
        }
    }

    @Nested
    @DisplayName("the rest of the pipeline")
    class UnaffectedByTheMapper {

        @Test
        @DisplayName("dead-letter publishing still works, with its metadata intact")
        void deadLetteringStillWorks() throws Exception {
            template.send("hostile-events", "k7", new TestEvent("evt-7", "FAIL", "n"));

            ConsumerRecord<String, byte[]> dead = readDeadLetter("hostile-events.DLT");

            assertThat(dead).isNotNull();
            assertThat(headerValue(dead, "kafka_dlt-original-topic")).isEqualTo("hostile-events");
            assertThat(headerValue(dead, DeadLetterHeaders.CONSUMER_GROUP)).isEqualTo("header-safety-test");
            assertThat(headerValue(dead, DeadLetterHeaders.EVENT_ID)).isEqualTo("evt-7");
        }

        /**
         * Header mapping and payload deserialization are separate boundaries;
         * hardening one must not quietly disable the other.
         */
        @Test
        @DisplayName("a malformed payload is still caught and dead-lettered")
        void payloadDeserializationStillGuarded() throws Exception {
            // Its own topic: a dead-letter read expects exactly one record,
            // and sharing a topic with the case above would make this depend
            // on which test ran first.
            sendWithHeaders("malformed-events", "k8", "{ not json at all", Map.of());

            ConsumerRecord<String, byte[]> dead = readDeadLetter("malformed-events.DLT");

            assertThat(headerValue(dead, "kafka_dlt-exception-fqcn"))
                    .contains("DeserializationException");
        }
    }

    private ConsumerRecord<String, byte[]> readDeadLetter(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-" + topic, "false", broker);
        props.put("key.deserializer", StringDeserializer.class);
        props.put("value.deserializer", ByteArrayDeserializer.class);
        try (Consumer<String, byte[]> consumer =
                     new DefaultKafkaConsumerFactory<String, byte[]>(props).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            return KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(25));
        }
    }

    private static String headerValue(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Raw headers arrive as byte[]; this reads one without assuming a type. */
    private static String asText(Object header) {
        if (header instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(header);
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        Listeners listeners() {
            return new Listeners();
        }
    }

    static class Listeners {

        final BlockingQueue<String> delivered = new ArrayBlockingQueue<>(16);
        final Map<String, Object> lastHeaders = new java.util.concurrent.ConcurrentHashMap<>();

        @KafkaListener(topics = "header-events", groupId = "header-safety-test")
        public void onEvent(TestEvent event, @Headers Map<String, Object> headers) {
            headers.forEach((name, value) -> {
                if (value != null) {
                    lastHeaders.put(name, value);
                }
            });
            delivered.add(event.eventId());
        }

        @KafkaListener(topics = "malformed-events", groupId = "header-safety-test")
        public void onMalformed(TestEvent event) {
            // Never reached: the payload does not deserialize.
        }

        @KafkaListener(topics = "hostile-events", groupId = "header-safety-test")
        public void onHostile(TestEvent event, @Header(name = "x-none", required = false) String ignored) {
            throw new IllegalStateException("always fails, to exercise recovery");
        }
    }
}
