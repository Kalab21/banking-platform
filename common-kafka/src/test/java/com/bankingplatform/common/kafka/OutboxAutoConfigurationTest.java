package com.bankingplatform.common.kafka;

import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
import com.bankingplatform.common.kafka.outbox.OutboxRelay;
import com.bankingplatform.common.kafka.outbox.OutboxRetention;
import com.bankingplatform.common.kafka.outbox.OutboxAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the outbox is really wired where a service publishes.
 *
 * <p>Written first this time. The two previous auto-configurations in this
 * module each shipped with their conditions evaluated against the wrong
 * ordering — auto-configurations are sorted by class name before ordering
 * metadata applies — and in both cases the bean was simply absent rather than
 * broken. One failed silently for a whole release; the other stopped six
 * services starting.
 */
@DisplayName("Outbox auto-configuration")
class OutboxAutoConfigurationTest {

    // Stand-ins, never connected to. What is under test is the wiring against
    // the real Boot auto-configurations, not SQL.
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> Mockito.mock(DataSource.class))
            .withBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(Mockito.mock(DataSource.class)))
            .withConfiguration(AutoConfigurations.of(
                    JdbcTemplateAutoConfiguration.class,
                    KafkaAutoConfiguration.class,
                    OutboxAutoConfiguration.class))
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092");

    @Test
    @DisplayName("registers the publisher where there is a database to write to")
    void registersPublisher() {
        context.run(loaded -> assertThat(loaded).hasSingleBean(OutboxPublisher.class));
    }

    @Test
    @DisplayName("registers the relay, without which the outbox only fills")
    void registersRelay() {
        context.run(loaded -> assertThat(loaded).hasSingleBean(OutboxRelay.class));
    }

    @Test
    @DisplayName("turns scheduling on, so the relay is actually called")
    void enablesScheduling() {
        // A @Scheduled method in a context without @EnableScheduling is never
        // invoked and never complains. Here that would mean events accumulate
        // in the table and nothing ever sends them — silent, and worse than
        // the lost event the outbox replaces.
        context.run(loaded -> assertThat(loaded)
                .hasBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"));
    }

    @Test
    @DisplayName("registers pruning of rows already sent")
    void registersRetention() {
        context.run(loaded -> assertThat(loaded).hasSingleBean(OutboxRetention.class));
    }

    @Test
    @DisplayName("the relay can be switched off without losing the publisher")
    void relayIsOptional() {
        // A service can write to the outbox and let another process drain it.
        context.withPropertyValues("kafka.outbox.enabled=false")
                .run(loaded -> {
                    assertThat(loaded).doesNotHaveBean(OutboxRelay.class);
                    assertThat(loaded).hasSingleBean(OutboxPublisher.class);
                });
    }

    @Test
    @DisplayName("stays out of the way in a service with no database")
    void quietWithoutDatabase() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        KafkaAutoConfiguration.class, OutboxAutoConfiguration.class))
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                .run(loaded -> assertThat(loaded).doesNotHaveBean(OutboxPublisher.class));
    }

    @Test
    @DisplayName("steps aside for a service that supplies its own publisher")
    void backsOffForUserSuppliedPublisher() {
        context.withBean("mine", OutboxPublisher.class, () -> (topic, event) -> { })
                .run(loaded -> assertThat(loaded).doesNotHaveBean("outboxPublisher"));
    }
}
