package com.bankingplatform.application.dto;

import com.bankingplatform.application.model.Offer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An offer as the customer sees it.
 *
 * <p>Carries the terms and what has happened to them, and nothing internal: no
 * decision id, no policy version, no reviewer note. Those belong to staff.
 */
public record OfferResponse(
        Long offerId,
        Long applicationId,
        String productType,
        String status,
        BigDecimal approvedAmount,
        BigDecimal apr,
        Integer termMonths,
        BigDecimal monthlyPayment,
        BigDecimal creditLimit,
        String cardTier,
        String currency,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime acceptedAt,
        LocalDateTime declinedAt) {

    public static OfferResponse from(Offer offer) {
        return new OfferResponse(
                offer.getId(),
                offer.getApplicationId(),
                offer.getProductType().name(),
                offer.getStatus().name(),
                offer.getApprovedAmount(),
                offer.getApr(),
                offer.getTermMonths(),
                offer.getMonthlyPayment(),
                offer.getCreditLimit(),
                offer.getCardTier(),
                offer.getCurrency(),
                offer.getCreatedAt(),
                offer.getExpiresAt(),
                offer.getAcceptedAt(),
                offer.getDeclinedAt());
    }
}
