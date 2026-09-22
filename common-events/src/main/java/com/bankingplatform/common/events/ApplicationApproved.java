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
 *
 * <p>Both amounts travel, and they are not the same figure. {@code
 * requestedAmount} is what the customer asked for and {@code approvedAmount} is
 * what the bank agreed to, which may be less. Only the approved figure may fund
 * a product; the requested one is carried so a consumer can record what the
 * application said without having to call back for it.
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
        BigDecimal requestedAmount,
        BigDecimal approvedAmount,
        BigDecimal offeredApr,
        Integer offeredTermMonths,
        BigDecimal offeredCreditLimit,
        String offeredCardTier) implements DomainEvent {

    /**
     * Version 2 added {@code approvedAmount}. Version 3 adds the offered terms:
     * the rate, the term, and a card's tier and limit.
     *
     * <p>Those terms used to be worked out by whichever service consumed this
     * event, from the credit score it carried — so the term came from a switch
     * statement in {@code loan-service} that had never seen the application,
     * and a customer who asked for twelve months was written forty-eight. The
     * terms the customer accepted now travel with the event, and the consumer
     * uses them rather than deriving its own.
     *
     * <p>Older payloads deserialise with the new fields null, and a consumer
     * that finds them null falls back to what it did before.
     */
    public static final int VERSION = 3;

    public static ApplicationApproved of(Long applicationId, Long userId, String productType,
                                         Long productId, Integer creditScore,
                                         BigDecimal requestedAmount, BigDecimal approvedAmount) {
        return of(applicationId, userId, productType, productId, creditScore,
                requestedAmount, approvedAmount, null, null, null, null);
    }

    public static ApplicationApproved of(Long applicationId, Long userId, String productType,
                                         Long productId, Integer creditScore,
                                         BigDecimal requestedAmount, BigDecimal approvedAmount,
                                         BigDecimal offeredApr, Integer offeredTermMonths,
                                         BigDecimal offeredCreditLimit, String offeredCardTier) {
        return new ApplicationApproved(EventMeta.newId(), EventTypes.APPLICATION_APPROVED, VERSION,
                EventMeta.now(), applicationId, userId, productType, productId, creditScore,
                requestedAmount, approvedAmount,
                offeredApr, offeredTermMonths, offeredCreditLimit, offeredCardTier);
    }

    /**
     * The amount that may actually be lent: what was approved, or what was
     * requested when an older payload carried no approved figure.
     */
    public BigDecimal fundableAmount() {
        return approvedAmount != null ? approvedAmount : requestedAmount;
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
