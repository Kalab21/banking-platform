package com.bankingplatform.common.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.kafka.core.ConsumerFactory;
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
 * <p>Logged rather than fatal. Refusing to start would be defensible, but this
 * module is also on the classpath of services that only produce, and a
 * reliability guard should not be able to take a healthy service down.
 */
class DeserializerWrappingCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DeserializerWrappingCheck.class);

    private final ConsumerFactory<?, ?> consumerFactory;

    DeserializerWrappingCheck(ConsumerFactory<?, ?> consumerFactory) {
        this.consumerFactory = consumerFactory;
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        Object deserializer = config.get("value.deserializer");
        if (deserializer == null) {
            return;
        }

        String name = deserializer instanceof Class<?> type ? type.getName() : String.valueOf(deserializer);
        if (name.equals(ErrorHandlingDeserializer.class.getName())) {
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
                name);
    }
}
