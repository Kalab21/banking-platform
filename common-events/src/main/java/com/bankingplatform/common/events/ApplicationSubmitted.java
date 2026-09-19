package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A customer applied for a product.
 *
 * <p>Consumer: {@code statistics-service}, which counts submissions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationSubmitted(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long applicationId,
        Long userId,
        String productType) implements DomainEvent {

    public static final int VERSION = 1;

    public static ApplicationSubmitted of(Long applicationId, Long userId, String productType) {
        return new ApplicationSubmitted(EventMeta.newId(), EventTypes.APPLICATION_SUBMITTED, VERSION,
                EventMeta.now(), applicationId, userId, productType);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(applicationId);
    }
}
