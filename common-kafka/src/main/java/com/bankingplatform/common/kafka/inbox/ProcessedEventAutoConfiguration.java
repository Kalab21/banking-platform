package com.bankingplatform.common.kafka.inbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers the processed-event guard wherever there is a database to hold it.
 *
 * <p>Conditional on a {@link JdbcTemplate}, so a service that only produces
 * events is unaffected by having this module on its classpath.
 *
 * <p><b>This requires PostgreSQL.</b> The guard claims an event with
 * {@code INSERT ... ON CONFLICT DO NOTHING} and the pruner bounds its deletes
 * by {@code ctid}; neither is portable SQL. That is a deliberate trade — the
 * whole platform runs on PostgreSQL, and the alternative, catching a duplicate
 * key exception, forces the caller's transaction into a rolled-back state on
 * the ordinary path rather than the exceptional one. A service on another
 * engine supplies its own {@link ProcessedEventGuard} and the bean below backs
 * off; see {@code docs/EVENTS.md}.
 */
// after JdbcTemplateAutoConfiguration, not DataSourceAutoConfiguration: the
// JdbcTemplate this waits for is created by the former, and auto-configurations
// are sorted by class name before ordering metadata applies. Ordered against
// the wrong one, @ConditionalOnBean below sees no JdbcTemplate yet and the
// guard silently never registers — which surfaces only when a consumer that
// needs it refuses to start.
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(ProcessedEventRetentionProperties.class)
public class ProcessedEventAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(ProcessedEventGuard.class)
    public ProcessedEventGuard processedEventGuard(JdbcTemplate jdbcTemplate) {
        return new JdbcProcessedEventGuard(jdbcTemplate);
    }

    /**
     * Pruning, kept separate so it carries its own {@code @EnableScheduling}.
     *
     * <p>Three of the six consuming services have no scheduling of their own,
     * and a {@code @Scheduled} method in a context without it is simply never
     * called — the table would grow exactly as before, silently. Enabling it
     * here is idempotent for the services that already declare it.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "kafka.inbox.retention", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public static class RetentionConfiguration {

        @Bean
        @ConditionalOnBean(JdbcTemplate.class)
        @ConditionalOnMissingBean(ProcessedEventRetention.class)
        public ProcessedEventRetention processedEventRetention(
                JdbcTemplate jdbcTemplate, ProcessedEventRetentionProperties properties) {
            return new ProcessedEventRetention(
                    jdbcTemplate, properties.getPeriod(), properties.getBatchSize());
        }
    }
}
