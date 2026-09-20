package com.bankingplatform.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What happens to a record this service cannot process.
 *
 * <p>Until now, nothing did. With no {@link CommonErrorHandler} bean the
 * container falls back to ten immediate attempts and then <em>commits the
 * offset anyway</em>, so a listener that threw because a database was briefly
 * unavailable lost the event and reported nothing. Before that, the listeners
 * caught every exception themselves and returned normally, which told the
 * container a failed record had succeeded — the same loss, one layer earlier.
 *
 * <p>Registered as an auto-configuration in a shared module because six
 * services consume from these topics and the answer to "how many times, how
 * long, and then where" should not be able to differ between them.
 *
 * <h2>Retry</h2>
 *
 * Bounded exponential backoff. The container's default of ten back-to-back
 * attempts is a tight loop: when the cause is a dependency being down, all ten
 * fail inside a few milliseconds and the record is dropped before the
 * dependency could possibly have recovered. Spacing the attempts is what makes
 * a retry worth attempting at all.
 *
 * <p>Retrying also blocks the partition, which is why the bound matters in the
 * other direction: every waiting record behind a failing one waits too.
 *
 * <h2>Recovery</h2>
 *
 * A record that has exhausted its retries is published to
 * {@code <topic>.DLT} and the offset is committed, so the partition moves on.
 * The record is kept, with enough context to understand and replay it, rather
 * than logged and discarded.
 *
 * <h2>What is not retried</h2>
 *
 * Spring treats a deserialization failure, a message-conversion failure and a
 * listener-signature mismatch as fatal, and sends them straight to the dead
 * letter topic. That is correct: none of them will succeed on a second
 * attempt, and retrying a poison record is how a partition stops forever.
 */
@AutoConfiguration
@ConditionalOnClass({KafkaTemplate.class, DefaultErrorHandler.class})
@EnableConfigurationProperties(KafkaRecoveryProperties.class)
public class KafkaRecoveryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaRecoveryAutoConfiguration.class);

    /**
     * Says so at start-up if the value deserializer is not wrapped.
     *
     * @see DeserializerWrappingCheck
     */
    @Bean
    @ConditionalOnBean(ConsumerFactory.class)
    @ConditionalOnMissingBean(DeserializerWrappingCheck.class)
    DeserializerWrappingCheck deserializerWrappingCheck(ConsumerFactory<?, ?> consumerFactory) {
        return new DeserializerWrappingCheck(consumerFactory);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaRecoveryErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            ProducerFactory<Object, Object> producerFactory,
            KafkaRecoveryProperties properties,
            @Value("${spring.kafka.consumer.group-id:unknown}") String consumerGroup) {

        DeadLetterPublishingRecoverer recoverer =
                recoverer(kafkaTemplate, bytesTemplate(producerFactory), properties, consumerGroup);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff(properties));

        // Logged per attempt so a partition that is retrying is visible before
        // anything reaches the dead letter topic.
        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Retrying {}-{} offset {} (attempt {} of {}): {}",
                        record.topic(), record.partition(), record.offset(),
                        deliveryAttempt, properties.getMaxRetries() + 1,
                        exception.getClass().getSimpleName()));

        return handler;
    }

    /**
     * Publishes the payload as raw bytes.
     *
     * <p>Needed for the one case the ordinary template cannot carry: when
     * deserialization is what failed, the record's value is the original
     * {@code byte[]} and the JSON serializer would re-encode it as a base64
     * string, turning the evidence into something that no longer resembles
     * what was published. The bytes are written through unchanged instead.
     *
     * <p>Built here rather than registered as a bean on purpose. A second
     * {@code KafkaTemplate} in the context makes every by-type injection of
     * one ambiguous, and the services' own producers inject a template that
     * way — so publishing a bean here risks handing a domain producer a
     * byte-array serializer it cannot use.
     */
    private KafkaTemplate<Object, Object> bytesTemplate(ProducerFactory<Object, Object> producerFactory) {
        Map<String, Object> configs = new HashMap<>(producerFactory.getConfigurationProperties());
        configs.put("key.serializer", StringSerializer.class);
        configs.put("value.serializer", ByteArraySerializer.class);
        // Removed because they belong to the JSON serializer this one replaces.
        configs.keySet().removeIf(key -> key.startsWith("spring.json"));
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(configs));
    }

    private DeadLetterPublishingRecoverer recoverer(KafkaTemplate<Object, Object> jsonTemplate,
                                                    KafkaTemplate<Object, Object> bytesTemplate,
                                                    KafkaRecoveryProperties properties,
                                                    String consumerGroup) {
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, jsonTemplate);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, exception) -> new TopicPartition(
                        record.topic() + properties.getDeadLetterSuffix(),
                        // -1 lets the broker choose. Reusing the original
                        // partition number would fail outright if the dead
                        // letter topic has fewer partitions than the source.
                        -1));

        recoverer.setHeadersFunction((record, exception) ->
                extraHeaders(record, exception, consumerGroup, properties));
        return recoverer;
    }

    /**
     * The context Spring does not add: who failed, and which event it was.
     *
     * @see DeadLetterHeaders
     */
    private org.apache.kafka.common.header.Headers extraHeaders(ConsumerRecord<?, ?> record,
                                                                Exception exception,
                                                                String consumerGroup,
                                                                KafkaRecoveryProperties properties) {
        List<Header> headers = new ArrayList<>();
        headers.add(header(DeadLetterHeaders.CONSUMER_GROUP, consumerGroup));
        headers.add(header(DeadLetterHeaders.ATTEMPTS, String.valueOf(properties.getMaxRetries() + 1)));

        Object payload = payloadOf(record, exception);
        String eventId = readEnvelopeField(payload, "eventId");
        if (eventId != null) {
            headers.add(header(DeadLetterHeaders.EVENT_ID, eventId));
        }
        String eventType = readEnvelopeField(payload, "eventType");
        if (eventType != null) {
            headers.add(header(DeadLetterHeaders.EVENT_TYPE, eventType));
        }
        return new org.apache.kafka.common.header.internals.RecordHeaders(headers);
    }

    /**
     * The payload to read an id out of.
     *
     * <p>When deserialization failed the record's value is null — the
     * deserializer could not produce one — and the original bytes are carried
     * on the {@link DeserializationException} instead. That is exactly the
     * record an operator most needs to identify, so it is worth looking in
     * both places.
     */
    private Object payloadOf(ConsumerRecord<?, ?> record, Exception exception) {
        if (record.value() != null) {
            return record.value();
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeserializationException deserialization) {
                return deserialization.getData();
            }
        }
        return null;
    }

    /**
     * Reads one envelope field without needing the event to be deserializable.
     *
     * <p>Deliberately not Jackson: the record that most needs an id on it is
     * the one whose deserialization failed, and parsing it properly is exactly
     * what did not work. Reflection covers the deserialized case and a narrow
     * string scan covers the raw-bytes case; either way a failure to read the
     * field is not allowed to fail the recovery.
     */
    private String readEnvelopeField(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof byte[] raw) {
                return scanJson(new String(raw, StandardCharsets.UTF_8), field);
            }
            var accessor = value.getClass().getMethod(field);
            Object result = accessor.invoke(value);
            return result == null ? null : String.valueOf(result);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Finds {@code "field":"value"} in a JSON document, or returns null. */
    private String scanJson(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int open = json.indexOf('"', json.indexOf(':', at + needle.length()) + 1);
        int close = open < 0 ? -1 : json.indexOf('"', open + 1);
        return close < 0 ? null : json.substring(open + 1, close);
    }

    private Header header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private BackOff backOff(KafkaRecoveryProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff(
                properties.getInitialInterval().toMillis(), properties.getMultiplier());
        backOff.setMaxInterval(properties.getMaxInterval().toMillis());
        // Counted as attempts after the first, so maxRetries=3 is four
        // deliveries in total.
        backOff.setMaxAttempts(properties.getMaxRetries());
        return backOff;
    }
}
