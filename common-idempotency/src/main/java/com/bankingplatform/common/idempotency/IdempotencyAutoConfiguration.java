package com.bankingplatform.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Bean
    @ConditionalOnBean({IdempotencyStore.class, ObjectMapper.class})
    @ConditionalOnMissingBean(IdempotencyGuard.class)
    public IdempotencyGuard idempotencyGuard(IdempotencyStore store, ObjectMapper objectMapper,
                                             OutcomeClassifier classifier) {
        return new IdempotencyGuard(store, objectMapper, classifier);
    }
}
