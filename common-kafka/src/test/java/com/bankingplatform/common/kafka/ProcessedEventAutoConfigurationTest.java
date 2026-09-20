package com.bankingplatform.common.kafka;

import com.bankingplatform.common.kafka.inbox.ProcessedEventAutoConfiguration;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the guard is really in the context of a service that has a database.
 *
 * <p>Written because it was not. The auto-configuration was ordered after
 * {@code DataSourceAutoConfiguration}, but the {@link org.springframework.jdbc.core.JdbcTemplate}
 * it waits for is created by {@code JdbcTemplateAutoConfiguration} — and since
 * auto-configurations are sorted by class name before ordering metadata is
 * applied, the condition ran too early and matched nothing.
 *
 * <p>Nothing failed at build time. It surfaced only when a consumer that needs
 * the guard refused to start, which is a slow and expensive way to learn it.
 */
@DisplayName("Processed-event auto-configuration")
class ProcessedEventAutoConfigurationTest {

    // A stand-in DataSource, never connected to. What is under test is the
    // ordering against the real JdbcTemplateAutoConfiguration, not SQL.
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> Mockito.mock(DataSource.class))
            .withConfiguration(AutoConfigurations.of(
                    JdbcTemplateAutoConfiguration.class,
                    ProcessedEventAutoConfiguration.class));

    @Test
    @DisplayName("registers the guard where there is a database to hold the claims")
    void registersGuard() {
        context.run(loaded -> assertThat(loaded).hasSingleBean(ProcessedEventGuard.class));
    }

    @Test
    @DisplayName("stays out of the way in a service with no database")
    void quietWithoutDatabase() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProcessedEventAutoConfiguration.class))
                .run(loaded -> assertThat(loaded).doesNotHaveBean(ProcessedEventGuard.class));
    }

    @Test
    @DisplayName("steps aside for a service that supplies its own guard")
    void backsOffForUserSuppliedGuard() {
        context.withBean("mine", ProcessedEventGuard.class, () -> (consumer, eventId) -> true)
                .run(loaded -> assertThat(loaded).doesNotHaveBean("processedEventGuard"));
    }
}
