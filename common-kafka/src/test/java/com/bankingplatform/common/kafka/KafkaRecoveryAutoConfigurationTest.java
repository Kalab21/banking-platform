package com.bankingplatform.common.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the beans this module promises are actually in the context.
 *
 * <p>Written because they were not. {@code @AutoConfiguration} was declared
 * without {@code after = KafkaAutoConfiguration.class}, and auto-configurations
 * are sorted by class name before ordering metadata is applied — so
 * {@code com.bankingplatform...} was evaluated before
 * {@code org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration}
 * and every {@code @ConditionalOnBean(ConsumerFactory.class)} saw a context
 * with no consumer factory in it yet.
 *
 * <p>The start-up guard therefore never registered in any of the six services,
 * and nothing failed: it is a bean whose only job is to log, so its absence
 * looks exactly like success. The integration tests could not catch it either,
 * because they assert what the error handler does, not that the context
 * contains the guard.
 */
@DisplayName("Kafka recovery auto-configuration")
class KafkaRecoveryAutoConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    KafkaAutoConfiguration.class, KafkaRecoveryAutoConfiguration.class))
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092");

    @Test
    @DisplayName("registers the error handler, so records are not dropped on failure")
    void registersErrorHandler() {
        context.run(loaded -> {
            assertThat(loaded).hasSingleBean(DefaultErrorHandler.class);
            assertThat(loaded).hasSingleBean(CommonErrorHandler.class);
        });
    }

    @Test
    @DisplayName("registers the deserializer guard, which ordering used to skip entirely")
    void registersDeserializerGuard() {
        context.run(loaded -> assertThat(loaded).hasSingleBean(DeserializerWrappingCheck.class));
    }

    @Test
    @DisplayName("steps aside for a service that defines its own error handler")
    void backsOffForUserSuppliedHandler() {
        context.withBean("myHandler", CommonErrorHandler.class, () -> new DefaultErrorHandler())
                .run(loaded -> assertThat(loaded).doesNotHaveBean("kafkaRecoveryErrorHandler"));
    }

    @Test
    @DisplayName("does nothing in a service that has no Kafka at all")
    void quietWithoutKafka() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaRecoveryAutoConfiguration.class))
                .run(loaded -> assertThat(loaded).doesNotHaveBean(DeserializerWrappingCheck.class));
    }
}
