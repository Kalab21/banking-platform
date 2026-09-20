package com.bankingplatform.common.kafka;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether a table this module needs actually exists in this service.
 *
 * <p>Every service on the platform has {@code common-kafka} on its classpath,
 * but not every service both consumes and publishes: a consumer has
 * {@code processed_event} and no {@code outbox_event}, a producer the reverse.
 * The scheduled jobs here would otherwise poll a table that is not there and
 * log a SQL error every second — noise that buries the failures worth reading,
 * in the services that are working correctly.
 *
 * <p>The answer is cached after the first look. Flyway runs during context
 * start-up and the scheduler starts after it, so by the first tick the schema
 * is settled and re-asking would only cost a query per poll.
 */
public final class TablePresence {

    private final JdbcTemplate jdbc;
    private final String table;
    private final AtomicReference<Boolean> present = new AtomicReference<>();

    public TablePresence(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    /** @return true if the table is visible on the current search path */
    public boolean exists() {
        Boolean cached = present.get();
        if (cached != null) {
            return cached;
        }
        Boolean found = jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
        boolean answer = Boolean.TRUE.equals(found);
        present.set(answer);
        return answer;
    }

    @Override
    public String toString() {
        return table;
    }
}
