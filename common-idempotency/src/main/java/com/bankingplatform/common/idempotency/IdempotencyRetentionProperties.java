package com.bankingplatform.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** How long settled idempotency records are kept. */
@ConfigurationProperties(prefix = "idempotency.retention")
public class IdempotencyRetentionProperties {

    /** Whether settled records are pruned on a schedule at all. */
    private boolean enabled = true;

    /**
     * How long a settled record is kept. Floored at
     * {@link IdempotencyRetention#MINIMUM}: a key has to outlive any client
     * that might still be retrying it.
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
