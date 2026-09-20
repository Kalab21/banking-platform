package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An application was approved, and the product it asked for should now exist.
 *
 * <p>This is the highest-consequence event on the platform: {@code loan-service}
 * and {@code credit-card-service} both listen, and each <em>creates a financial
 * product</em> when it matches. A duplicate delivery therefore issues a second
 * loan or a second card, which is why {@link #eventId()} matters here more than
 * anywhere else.
 *
 * <p>The old payload carried {@code applicationType} and {@code productType}
 * with the same value, because one consumer read each. One name now, and the
 * consumers that route on it share this constant instead of two literals.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationApproved(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long applicationId,
        Long userId,
        String productType,
        Long productId,
        Integer creditScore,
        BigDecimal requestedAmount) implements DomainEvent {

    public static final int VERSION = 1;

    public static ApplicationApproved of(Long applicationId, Long userId, String productType,
                                         Long productId, Integer creditScore, BigDecimal requestedAmount) {
        return new ApplicationApproved(EventMeta.newId(), EventTypes.APPLICATION_APPROVED, VERSION,
                EventMeta.now(), applicationId, userId, productType, productId, creditScore, requestedAmount);
    }

    /**
     * Keyed by application, so the submitted and decided events for one
     * application are ordered with respect to each other.
     */
    @Override
    public String partitionKey() {
        return String.valueOf(applicationId);
    }
}
