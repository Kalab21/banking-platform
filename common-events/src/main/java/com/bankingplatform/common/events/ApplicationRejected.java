package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * An application was declined.
 *
 * <p>Consumers: {@code notification-service} (tells the customer, naming the
 * product) and {@code statistics-service} (counts it).
 *
 * <p>The notification read {@code productType}, which this event never carried
 * — only {@code applicationType} — so every rejection notice said "your
 * product application" with the word "product" as a literal fallback. The
 * field is named the same as on the other two application events now.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationRejected(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long applicationId,
        Long userId,
        String productType,
        String reason) implements DomainEvent {

    public static final int VERSION = 1;

    public static ApplicationRejected of(Long applicationId, Long userId, String productType, String reason) {
        return new ApplicationRejected(EventMeta.newId(), EventTypes.APPLICATION_REJECTED, VERSION,
                EventMeta.now(), applicationId, userId, productType, reason);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(applicationId);
    }
}
