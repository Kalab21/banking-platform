package com.bankingplatform.common.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
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
// after Boot's Kafka auto-configuration on purpose: auto-configurations are
// sorted by class name before ordering metadata is applied, so without this
// "com.bankingplatform..." is evaluated before
// "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration" and
// every @ConditionalOnBean below sees a context with no ConsumerFactory in it
// yet. The start-up guard silently never registers.
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, DefaultErrorHandler.class})
@EnableConfigurationProperties(KafkaRecoveryProperties.class)
public class KafkaRecoveryAutoConfiguration implements DisposableBean {

    /**
     * Held so it can be closed.
     *
     * <p>{@link DefaultKafkaProducerFactory} only closes its producer in
     * {@code destroy()}. Built outside the container, nothing would ever call
     * that, and the sender thread, broker sockets and buffer memory would
     * outlive the context — one leaked producer per refresh.
     */
    private volatile DefaultKafkaProducerFactory<Object, Object> deadLetterProducerFactory;

    /**
     * Empty in a service with no metrics registry, which is why every use of
     * it is guarded rather than assumed.
     */
    private Optional<DeadLetterCounter> deadLetters = Optional.empty();

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry registry) {
        this.deadLetters = Optional.ofNullable(registry).map(DeadLetterCounter::new);
    }

    private static final Logger log = LoggerFactory.getLogger(KafkaRecoveryAutoConfiguration.class);

    /**
     * Says so at start-up if the value deserializer is not wrapped.
     *
     * @see DeserializerWrappingCheck
     */
    @Bean
    @ConditionalOnBean(ConsumerFactory.class)
    @ConditionalOnMissingBean(DeserializerWrappingCheck.class)
    DeserializerWrappingCheck deserializerWrappingCheck(
            ConsumerFactory<?, ?> consumerFactory,
            ObjectProvider<KafkaListenerEndpointRegistry> registries,
            ObjectProvider<MessageListenerContainer> containers) {
        return new DeserializerWrappingCheck(consumerFactory, registries, containers);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    // Both are required to build a recoverer, and neither exists unless Kafka
    // is actually configured. Without this condition a service that merely has
    // this module on its classpath fails to start, which is a worse outcome
    // than having no dead-letter policy.
    @ConditionalOnBean({KafkaTemplate.class, ProducerFactory.class})
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
        this.deadLetterProducerFactory = new DefaultKafkaProducerFactory<>(configs);
        return new KafkaTemplate<>(this.deadLetterProducerFactory);
    }

    @Override
    public void destroy() {
        DefaultKafkaProducerFactory<Object, Object> factory = this.deadLetterProducerFactory;
        if (factory != null) {
            factory.destroy();
        }
    }

    /**
     * Counts records that reach a dead letter topic, tagged by where they
     * came from.
     *
     * <p>Optional, because this module sits on the classpath of services that
     * may have no metrics registry, and a shared module must not force one on
     * them. Where a registry exists, {@code banking.kafka.deadletter} is the
     * number to alert on: unlike a retry, a dead letter means the platform
     * has given up on a record.
     */
    private static final class DeadLetterCounter {

        private final MeterRegistry registry;

        private DeadLetterCounter(MeterRegistry registry) {
            this.registry = registry;
        }

        void increment(String sourceTopic) {
            registry.counter("banking.kafka.deadletter", "topic", sourceTopic).increment();
        }
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

        recoverer.setHeadersFunction((record, exception) -> {
            // The one place a record is known to be beyond retrying. A log
            // line here is true and unwatched; a counter is the thing an
            // alert can be hung on, and a dead letter is exactly the event
            // worth waking someone for.
            //
            // Counted in the headers function rather than by wrapping the
            // recoverer, because that function runs once per recovered record
            // and is the only hook Spring offers that sees both the record
            // and the failure.
            deadLetters.ifPresent(counter -> counter.increment(record.topic()));
            return extraHeaders(record, exception, consumerGroup, properties);
        });
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
        // The group of the container that actually failed, not the
        // service-wide property: @KafkaListener can set its own groupId, and
        // this header is the thing that tells an operator who to replay to.
        String group = KafkaUtils.getConsumerGroupId();
        headers.add(header(DeadLetterHeaders.CONSUMER_GROUP, group != null ? group : consumerGroup));
        // What actually happened, not what the policy allows. A
        // non-retryable failure — a payload that cannot be deserialized — is
        // recovered after a single delivery, so stating the configured
        // maximum here would tell an operator the record was retried for
        // several seconds against a dependency when it was never retried at
        // all.
        headers.add(header(DeadLetterHeaders.ATTEMPTS, String.valueOf(deliveryCount(exception, properties))));

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
     * How many times the record was actually delivered.
     *
     * <p>The recoverer is reached by exactly two routes, and they differ:
     * a retryable failure arrives having exhausted every attempt, and a fatal
     * one — a payload that cannot be deserialized, a listener signature that
     * does not match — is recovered after a single delivery without being
     * retried at all.
     *
     * <p>Reporting the configured maximum for both would tell an operator
     * that an unreadable record was retried for several seconds against a
     * dependency, when the listener was never invoked once.
     */
    private int deliveryCount(Exception exception, KafkaRecoveryProperties properties) {
        return isFatal(exception) ? 1 : properties.getMaxRetries() + 1;
    }

    /** The failures {@link DefaultErrorHandler} refuses to retry. */
    private boolean isFatal(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeserializationException
                    || cause instanceof org.springframework.messaging.converter.MessageConversionException
                    || cause instanceof org.springframework.kafka.support.converter.ConversionException
                    || cause instanceof ClassCastException) {
                return true;
            }
        }
        return false;
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

    /**
     * Finds {@code "field":"value"} in a JSON document, or returns null.
     *
     * <p>Returns null rather than something plausible when the document is
     * truncated or the value is not a string. Both were wrong before: a
     * missing colon made {@code indexOf} restart from zero and report the
     * first quoted token in the document, so {@code {"eventId"} yielded the
     * id {@code "eventId"} — a confident wrong answer on exactly the
     * truncated payload this method exists to read.
     */
    private String scanJson(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int cursor = colon + 1;
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
            cursor++;
        }
        // Only a string value is an identifier worth reporting.
        if (cursor >= json.length() || json.charAt(cursor) != '"') {
            return null;
        }
        int close = json.indexOf('"', cursor + 1);
        return close < 0 ? null : json.substring(cursor + 1, close);
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
