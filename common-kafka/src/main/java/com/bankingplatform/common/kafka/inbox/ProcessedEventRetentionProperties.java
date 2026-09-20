package com.bankingplatform.common.kafka.inbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** How long processed-event claims are kept, and how they are removed. */
@ConfigurationProperties(prefix = "kafka.inbox.retention")
public class ProcessedEventRetentionProperties {

    /** Whether expired claims are pruned on a schedule at all. */
    private boolean enabled = true;

    /**
     * How long a claim is kept. Floored at
     * {@link ProcessedEventRetention#MINIMUM}: a claim must outlive the
     * broker's retention of the event it guards, or a replay finds no claim.
     */
    private Duration period = Duration.ofDays(30);

    /** Rows removed per statement, so no single delete holds a long lock. */
    private int batchSize = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPeriod() {
        return period;
    }

    public void setPeriod(Duration period) {
        this.period = period;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
