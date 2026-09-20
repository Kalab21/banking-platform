package com.bankingplatform.common.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.support.SimpleKafkaHeaderMapper;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;

/**
 * Kafka headers are transport metadata, and nothing more.
 *
 * <p>A record's <em>payload</em> and its <em>headers</em> are two different
 * trust boundaries, and this platform had only secured the first one. The
 * payload contract is explicit: type headers are off, routing is the
 * {@code eventType} field, and only the contract package may be instantiated.
 * None of that constrains header handling, which Spring maps separately.
 *
 * <p>Left at the framework default, an inbound record can carry a
 * {@code spring_json_header_types} header naming a Java class, and the
 * mapper will construct it while building the message for the listener. The
 * producer controls those bytes. That is the path behind CVE-2026-41731
 * against the Spring Kafka version this platform runs, and it is reachable
 * whatever the payload deserializer is configured to do.
 *
 * <p>{@link SimpleKafkaHeaderMapper} removes the capability rather than
 * trying to police it. Headers arrive as raw bytes, no type name is honoured,
 * and no Java object is constructed from anything a producer wrote. Northbank
 * has no use for typed header objects: the headers that matter here are a
 * correlation id, trace context, event identity and dead-letter metadata, all
 * of which are strings or bytes.
 *
 * <p>Deliberately <em>not</em> done by widening a trusted-package list. An
 * allowlist still performs reflective construction and still depends on the
 * framework's own parsing being correct, which is the thing the advisory says
 * it is not. Turning the feature off is a smaller claim and a smaller
 * surface.
 *
 * <p>This does not patch the dependency. The package remains the affected
 * version until the platform is modernised; what changes is that the
 * vulnerable code path is no longer on any listener this platform runs.
 * Recorded in {@code docs/SECURITY.md}.
 *
 * <p>Separate from {@link KafkaRecoveryAutoConfiguration} because it is a
 * separate concern: one decides what happens to a record that fails, this
 * decides what a record is allowed to make the JVM do before a listener ever
 * sees it. A service can reasonably want one without the other.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({RecordMessageConverter.class, SimpleKafkaHeaderMapper.class})
public class KafkaHeaderSafetyAutoConfiguration {

    /**
     * The converter Spring Boot hands to every {@code @KafkaListener} factory.
     *
     * <p>Boot's {@code ConcurrentKafkaListenerContainerFactoryConfigurer}
     * takes a {@link RecordMessageConverter} bean if one exists, so supplying
     * it here changes the effective listener configuration rather than only
     * the contents of this module.
     *
     * <p>Backs off if a service defines its own, so this cannot silently
     * override a deliberate local choice — though a service doing that takes
     * the header-mapping decision back with it.
     */
    @Bean
    @ConditionalOnMissingBean(RecordMessageConverter.class)
    public RecordMessageConverter rawKafkaHeaderMessageConverter() {
        MessagingMessageConverter converter = new MessagingMessageConverter();
        converter.setHeaderMapper(new SimpleKafkaHeaderMapper());
        return converter;
    }
}
