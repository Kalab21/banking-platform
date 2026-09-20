package com.bankingplatform.common.kafka.outbox;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Wires the outbox wherever a service both has a database and publishes.
 *
 * <p><b>PostgreSQL only</b>, like the processed-event guard: the relay claims
 * a key with {@code pg_try_advisory_xact_lock} and the prune bounds itself by
 * {@code ctid}. See {@code docs/EVENTS.md}.
 */
// Ordered after all three of the auto-configurations whose beans are required
// here, because auto-configurations are sorted by class name before ordering
// metadata applies — a condition that runs too early simply matches nothing,
// and the resulting bean is missing rather than broken. Both previous modules
// in this package got exactly that wrong.
@AutoConfiguration(after = {
        JdbcTemplateAutoConfiguration.class,
        KafkaAutoConfiguration.class})
@ConditionalOnClass({JdbcTemplate.class, KafkaTemplate.class})
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    /**
     * Serialises with spring-kafka's own mapper, not the application's.
     *
     * <p>The {@code JsonSerializer} these events used to go through does not
     * use Boot's {@code ObjectMapper}; it builds its own through
     * {@link JacksonUtils#enhancedObjectMapper()}. That mapper is therefore the
     * definition of the current wire format, down to how an {@code Instant} is
     * written and whether nulls appear at all.
     *
     * <p>Boot's mapper is configured by the application's own Jackson
     * properties and by whatever modules are on its classpath, so the two
     * agree only by coincidence. Injecting it here would have re-encoded every
     * event the moment publishing moved to the outbox — a wire-format change
     * for every consumer, produced by a refactor that was supposed to change
     * only where the event is written.
     *
     * <p>{@code OutboxIT} pins this: the stored payload must be byte-for-byte
     * what the serializer would have produced.
     */
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(OutboxPublisher.class)
    public OutboxPublisher outboxPublisher(JdbcTemplate jdbcTemplate) {
        return new JdbcOutboxPublisher(jdbcTemplate, JacksonUtils.enhancedObjectMapper());
    }

    /**
     * The relay and its pruning, with scheduling switched on here.
     *
     * <p>Not every publishing service declares {@code @EnableScheduling}, and
     * a {@code @Scheduled} method in a context without it is never called and
     * never complains — the outbox would fill and nothing would drain it,
     * which is a worse failure than the lost event it replaces.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "kafka.outbox", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public static class RelayConfiguration implements DisposableBean {

        private DefaultKafkaProducerFactory<String, byte[]> relayProducerFactory;

        @Bean
        @ConditionalOnBean({JdbcTemplate.class, ProducerFactory.class,
                PlatformTransactionManager.class})
        @ConditionalOnMissingBean(OutboxRelay.class)
        public OutboxRelay outboxRelay(JdbcTemplate jdbcTemplate,
                                       ProducerFactory<Object, Object> producerFactory,
                                       OutboxProperties properties,
                                       PlatformTransactionManager transactionManager) {
            return new OutboxRelay(jdbcTemplate, relayTemplate(producerFactory), properties,
                    transactionManager);
        }

        @Bean
        @ConditionalOnBean(JdbcTemplate.class)
        @ConditionalOnMissingBean(OutboxRetention.class)
        public OutboxRetention outboxRetention(JdbcTemplate jdbcTemplate,
                                               OutboxProperties properties) {
            return new OutboxRetention(jdbcTemplate, properties.getRetention(),
                    properties.getBatchSize());
        }

        /**
         * Sends the stored payload as the bytes it already is.
         *
         * <p>The service's own template serialises with Jackson, which would
         * treat the stored JSON as a string to be encoded again — consumers
         * would receive a quoted, escaped document rather than the event. The
         * bytes are what was serialised inside the caller's transaction, and
         * writing them through unchanged is the whole point: what is stored is
         * what goes on the wire.
         *
         * <p>Built privately rather than published as a bean. Two
         * {@code KafkaTemplate} beans make an injection by type ambiguous, and
         * the domain producers in these services inject one that way.
         */
        private KafkaTemplate<String, byte[]> relayTemplate(
                ProducerFactory<Object, Object> producerFactory) {
            Map<String, Object> configs = new HashMap<>(producerFactory.getConfigurationProperties());
            configs.put("key.serializer", StringSerializer.class);
            configs.put("value.serializer", ByteArraySerializer.class);
            // Settings that belong to the JSON serializer being replaced.
            configs.keySet().removeIf(key -> key.startsWith("spring.json"));
            this.relayProducerFactory = new DefaultKafkaProducerFactory<>(configs);
            return new KafkaTemplate<>(this.relayProducerFactory);
        }

        @Override
        public void destroy() {
            DefaultKafkaProducerFactory<String, byte[]> factory = this.relayProducerFactory;
            if (factory != null) {
                factory.destroy();
            }
        }
    }
}
