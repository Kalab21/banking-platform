package com.bankingplatform.common.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.Map;

/**
 * Checks at start-up that this service's value deserializer is wrapped.
 *
 * <p>The retry and dead-letter policy only covers failures the container can
 * see. Deserialization happens <em>before</em> the listener, so if the value
 * deserializer is not wrapped in {@link ErrorHandlingDeserializer}, a record
 * that cannot be read never reaches the error handler at all: the container
 * retries the same offset forever and that partition stops for good. One
 * malformed record takes the consumer down permanently, silently, and the
 * service still reports itself healthy.
 *
 * <p>That is a one-line configuration mistake with a disproportionate
 * consequence, and nothing in a normal test would catch it — everything works
 * until the day a bad record arrives. So it is checked where it cannot be
 * forgotten: a service that consumes without the wrapper says so loudly on
 * every start-up.
 *
 * <p>It asks whether the service <em>consumes</em> before it complains, and
 * that distinction is the point rather than a detail. Configuration is not
 * consumption: a service can carry a {@code spring.kafka.consumer} block it
 * never uses, and Boot builds a {@link ConsumerFactory} from it either way.
 * Warning on the configuration alone made a producer-only service report a
 * permanently stopped partition it does not have — and an alarm that is
 * routinely wrong is worse than no alarm, because it teaches whoever reads the
 * log to skip this line on the day it is real.
 *
 * <p>Logged rather than fatal. Refusing to start would be defensible, but this
 * module is also on the classpath of services that only produce, and a
 * reliability guard should not be able to take a healthy service down.
 */
class DeserializerWrappingCheck implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DeserializerWrappingCheck.class);

    private final ConsumerFactory<?, ?> consumerFactory;
    private final ObjectProvider<KafkaListenerEndpointRegistry> registries;
    private final ObjectProvider<MessageListenerContainer> containers;

    DeserializerWrappingCheck(ConsumerFactory<?, ?> consumerFactory,
                              ObjectProvider<KafkaListenerEndpointRegistry> registries,
                              ObjectProvider<MessageListenerContainer> containers) {
        this.consumerFactory = consumerFactory;
        this.registries = registries;
        this.containers = containers;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!consumes() || isWrapped()) {
            return;
        }

        log.error("""
                        Kafka value deserializer is {} rather than ErrorHandlingDeserializer. \
                        A record that cannot be deserialized will fail before the listener, so the \
                        retry and dead-letter policy cannot see it, and the container will retry the \
                        same offset indefinitely — that partition will stop permanently on the first \
                        malformed record. Set spring.kafka.consumer.value-deserializer to \
                        ErrorHandlingDeserializer and move the current one to \
                        spring.deserializer.value.delegate.class.""",
                configuredDeserializer());
    }

    /**
     * Whether anything in this service actually reads from a topic.
     *
     * <p>Checked at {@link ContextRefreshedEvent} rather than on construction
     * because {@code @KafkaListener} endpoints become containers while the
     * context is still refreshing; asked any earlier the registry is honestly
     * empty and every consumer would look like a producer.
     *
     * <p>The registry covers {@code @KafkaListener}. A container declared
     * directly as a bean never reaches it, so those are counted too — missing
     * one would put the guard back to being silently wrong, in the direction
     * that matters.
     */
    private boolean consumes() {
        KafkaListenerEndpointRegistry registry = registries.getIfAvailable();
        if (registry != null && !registry.getListenerContainers().isEmpty()) {
            return true;
        }
        return containers.stream().findAny().isPresent();
    }

    private boolean isWrapped() {
        String name = configuredDeserializer();
        return name == null || name.equals(ErrorHandlingDeserializer.class.getName());
    }

    /** The configured value deserializer's class name, or null when none is set. */
    private String configuredDeserializer() {
        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        Object deserializer = config.get("value.deserializer");
        if (deserializer == null) {
            return null;
        }
        return deserializer instanceof Class<?> type ? type.getName() : String.valueOf(deserializer);
    }
}
