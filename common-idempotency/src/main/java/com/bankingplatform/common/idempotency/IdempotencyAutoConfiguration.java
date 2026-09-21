package com.bankingplatform.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the idempotency guard wherever a service has a database to record
 * attempts in.
 *
 * <p>The service still has to create the {@code idempotency_record} table and
 * call the guard from its controllers; this only removes the wiring.
 *
 * <p>The {@link OutcomeClassifier} is left to the service. It gets the
 * conservative one by default, which treats every failure as having an unknown
 * effect on the balance — correct, and deliberately inconvenient enough that a
 * service moving real money will replace it.
 */
// Ordered after everything whose beans are read below, because
// auto-configurations are sorted by class name before ordering metadata
// applies: "com.bankingplatform..." runs before "org.springframework..."
// unless it says otherwise, and a condition that runs too early matches
// nothing and leaves the bean silently absent. That has happened three times
// in the Kafka module, twice on the transaction manager specifically.
@AutoConfiguration(after = {
        JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JacksonAutoConfiguration.class})
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(IdempotencyRetentionProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore idempotencyStore(JdbcTemplate jdbcTemplate) {
        return new IdempotencyStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(OutcomeClassifier.class)
    public OutcomeClassifier conservativeOutcomeClassifier() {
        return OutcomeClassifier.conservative();
    }

    /**
     * Reports the records a person has to resolve, where a registry exists.
     *
     * <p>Conditional on the registry rather than requiring it: this module is
     * on the classpath of services that may have no metrics stack, and a
     * shared module has no business forcing one on them.
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean({JdbcTemplate.class, MeterRegistry.class})
    @ConditionalOnMissingBean(IdempotencyMetrics.class)
    public IdempotencyMetrics idempotencyMetrics(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        return new IdempotencyMetrics(jdbcTemplate, registry);
    }

    /**
     * Pruning, with its own {@code @EnableScheduling}.
     *
     * <p>A {@code @Scheduled} method in a context without it is never called
     * and never complains, which on this platform has meant a table growing
     * for weeks while everything looked healthy.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "idempotency.retention", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public static class RetentionConfiguration {

        @Bean
        @ConditionalOnBean(JdbcTemplate.class)
        @ConditionalOnMissingBean(IdempotencyRetention.class)
        public IdempotencyRetention idempotencyRetention(JdbcTemplate jdbcTemplate,
                                                         IdempotencyRetentionProperties properties) {
            return new IdempotencyRetention(jdbcTemplate, properties.getPeriod(),
                    properties.getBatchSize());
        }
    }

    @Bean
    @ConditionalOnBean({IdempotencyStore.class, ObjectMapper.class})
    @ConditionalOnMissingBean(IdempotencyGuard.class)
    public IdempotencyGuard idempotencyGuard(IdempotencyStore store, ObjectMapper objectMapper,
                                             OutcomeClassifier classifier) {
        return new IdempotencyGuard(store, objectMapper, classifier);
    }
}
