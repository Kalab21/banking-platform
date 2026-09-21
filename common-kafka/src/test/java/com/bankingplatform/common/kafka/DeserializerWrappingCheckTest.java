package com.bankingplatform.common.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who the start-up deserializer warning is actually for.
 *
 * <p>The guard exists because an unwrapped value deserializer stops a
 * partition permanently on the first malformed record, silently, while the
 * service still reports itself healthy. That reasoning only applies to a
 * service that reads from a topic at all — and the check used to ask whether a
 * deserializer was <em>configured</em>, which is a different question. A
 * producer-only service carrying an unused consumer block answered yes and
 * reported a stopped partition it does not have.
 */
@DisplayName("The deserializer wrapping check")
class DeserializerWrappingCheckTest {

    private ListAppender<ILoggingEvent> logged;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeserializerWrappingCheck.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logged);
        logged.stop();
    }

    private ConsumerFactory<?, ?> configuredWith(Object valueDeserializer) {
        ConsumerFactory<?, ?> factory = mock(ConsumerFactory.class);
        Map<String, Object> config = valueDeserializer == null
                ? Map.of("key.deserializer", StringDeserializer.class)
                : Map.of("key.deserializer", StringDeserializer.class,
                         "value.deserializer", valueDeserializer);
        when(factory.getConfigurationProperties()).thenReturn(config);
        return factory;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<KafkaListenerEndpointRegistry> registryHolding(
            MessageListenerContainer... containers) {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        if (containers.length == 0) {
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenReturn((List) List.of(containers));
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MessageListenerContainer> beansOf(MessageListenerContainer... containers) {
        ObjectProvider<MessageListenerContainer> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenReturn(Stream.of(containers));
        return provider;
    }

    private void start(DeserializerWrappingCheck check) {
        check.onApplicationEvent(mock(ContextRefreshedEvent.class));
    }

    private boolean warned() {
        return logged.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("says nothing when the service only produces, whatever its consumer config says")
    void producerOnlyIsSilent() {
        // transaction-service carried a consumer block it never used. The
        // config alone is not a consumer, and warning on it taught whoever
        // reads the log to skip this line on the day it is real.
        start(new DeserializerWrappingCheck(configuredWith(JsonDeserializer.class),
                registryHolding(), beansOf()));

        assertThat(warned())
                .as("a service with no listeners has no partition to stop")
                .isFalse();
    }

    @Test
    @DisplayName("still complains when a real consumer has an unwrapped deserializer")
    void realConsumerUnwrappedStillWarns() {
        start(new DeserializerWrappingCheck(configuredWith(JsonDeserializer.class),
                registryHolding(mock(MessageListenerContainer.class)), beansOf()));

        assertThat(warned())
                .as("this is the case the guard exists for and must not have been weakened")
                .isTrue();
    }

    @Test
    @DisplayName("says nothing when a real consumer is wrapped properly")
    void realConsumerWrappedIsSilent() {
        start(new DeserializerWrappingCheck(configuredWith(ErrorHandlingDeserializer.class),
                registryHolding(mock(MessageListenerContainer.class)), beansOf()));

        assertThat(warned()).isFalse();
    }

    @Test
    @DisplayName("counts a container declared as a bean, not only annotated listeners")
    void containerBeansCountAsConsuming() {
        // The registry only knows about @KafkaListener. Missing a directly
        // declared container would put the guard back to being silently wrong,
        // in the direction that matters.
        start(new DeserializerWrappingCheck(configuredWith(JsonDeserializer.class),
                registryHolding(), beansOf(mock(MessageListenerContainer.class))));

        assertThat(warned()).isTrue();
    }

    @Test
    @DisplayName("says nothing when no value deserializer is configured at all")
    void noDeserializerConfigured() {
        start(new DeserializerWrappingCheck(configuredWith(null),
                registryHolding(mock(MessageListenerContainer.class)), beansOf()));

        assertThat(warned()).isFalse();
    }

    @Test
    @DisplayName("reads a deserializer given as a class name string, not only as a Class")
    void deserializerGivenAsString() {
        start(new DeserializerWrappingCheck(configuredWith(JsonDeserializer.class.getName()),
                registryHolding(mock(MessageListenerContainer.class)), beansOf()));

        assertThat(warned()).isTrue();
    }
}
