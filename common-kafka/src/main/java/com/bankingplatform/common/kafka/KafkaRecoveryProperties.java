package com.bankingplatform.common.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How hard to try before giving up on a record.
 *
 * <p>Configuration rather than constants: how many times to retry a failing
 * event, and how long to wait between attempts, is an operational decision
 * that depends on what usually breaks. An operator changing it should not need
 * a rebuild.
 */
@ConfigurationProperties(prefix = "kafka.recovery")
public class KafkaRecoveryProperties {

    /**
     * Retries after the first attempt, so {@code 3} means four deliveries in
     * total before the record is dead-lettered.
     *
     * <p>Bounded on purpose. The container's default is ten attempts with no
     * delay at all, which for a dependency that is down is a tight loop that
     * accomplishes nothing and then drops the event.
     */
    private int maxRetries = 3;

    /** Wait before the first retry. */
    private Duration initialInterval = Duration.ofMillis(500);

    /** Each wait is this multiple of the last, so attempts spread out. */
    private double multiplier = 3.0;

    /** No wait grows past this, so a slow dependency cannot stall a partition for minutes. */
    private Duration maxInterval = Duration.ofSeconds(10);

    /**
     * Suffix for the dead-letter topic: {@code account-events} becomes
     * {@code account-events.DLT}.
     */
    private String deadLetterSuffix = ".DLT";

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getInitialInterval() {
        return initialInterval;
    }

    public void setInitialInterval(Duration initialInterval) {
        this.initialInterval = initialInterval;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public Duration getMaxInterval() {
        return maxInterval;
    }

    public void setMaxInterval(Duration maxInterval) {
        this.maxInterval = maxInterval;
    }

    public String getDeadLetterSuffix() {
        return deadLetterSuffix;
    }

    public void setDeadLetterSuffix(String deadLetterSuffix) {
        this.deadLetterSuffix = deadLetterSuffix;
    }
}
