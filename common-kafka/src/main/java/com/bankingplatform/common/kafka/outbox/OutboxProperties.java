package com.bankingplatform.common.kafka.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** How the relay drains the outbox, and how long sent rows are kept. */
@ConfigurationProperties(prefix = "kafka.outbox")
public class OutboxProperties {

    /** Whether the relay runs. A service can write to the outbox without one. */
    private boolean enabled = true;

    /** Milliseconds between polls, as the scheduler reads it. */
    private long pollInterval = 1000;

    /**
     * Keys drained per poll. Each is locked for the whole tick, so this bounds
     * how much of the connection pool the relay can hold at once.
     */
    private int keysPerPoll = 20;

    /** Events sent per key per poll. */
    private int batchSize = 50;

    /**
     * How long to wait for the broker to acknowledge one event before giving
     * up on that key for this tick.
     */
    private Duration sendTimeout = Duration.ofSeconds(10);

    /**
     * Failures on one row before the log moves from warning to error. Past
     * this the key is stalled, which is an operational problem rather than a
     * transient one.
     */
    private int warnAfterAttempts = 5;

    /**
     * How long a sent row is kept before pruning. Long enough to be evidence
     * when something is being investigated; unsent rows are never pruned.
     */
    private Duration retention = Duration.ofDays(7);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(long pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getKeysPerPoll() {
        return keysPerPoll;
    }

    public void setKeysPerPoll(int keysPerPoll) {
        this.keysPerPoll = keysPerPoll;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public int getWarnAfterAttempts() {
        return warnAfterAttempts;
    }

    public void setWarnAfterAttempts(int warnAfterAttempts) {
        this.warnAfterAttempts = warnAfterAttempts;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }
}
