package com.bankingplatform.common.kafka.inbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers the processed-event guard wherever there is a database to hold it.
 *
 * <p>Conditional on a {@link JdbcTemplate}, so a service that only produces
 * events is unaffected by having this module on its classpath.
 */
// after JdbcTemplateAutoConfiguration, not DataSourceAutoConfiguration: the
// JdbcTemplate this waits for is created by the former, and auto-configurations
// are sorted by class name before ordering metadata applies. Ordered against
// the wrong one, @ConditionalOnBean below sees no JdbcTemplate yet and the
// guard silently never registers — which surfaces only when a consumer that
// needs it refuses to start.
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
public class ProcessedEventAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(ProcessedEventGuard.class)
    public ProcessedEventGuard processedEventGuard(JdbcTemplate jdbcTemplate) {
        return new JdbcProcessedEventGuard(jdbcTemplate);
    }
}
