package com.bankingplatform.application.underwriting;

import java.math.BigDecimal;

/**
 * The terms Northbank is prepared to lend on.
 *
 * <p>Computed once, by the policy, at the moment of the decision — and then
 * carried unchanged through the offer, the customer's acceptance, the event and
 * the product. Each service deriving its own version of these is how a customer
 * came to ask for twelve months and be written a loan for forty-eight: the
 * term was decided in a switch statement in {@code loan-service} that had never
 * seen the application.
 *
 * @param apr             the rate, as a percentage
 * @param termMonths      the term, null for a product without one
 * @param monthlyPayment  the estimated instalment, null where there is no term
 * @param creditLimit     a card's limit, null for a loan
 * @param cardTier        a card's tier, null for a loan
 */
public record OfferedTerms(
        BigDecimal apr,
        Integer termMonths,
        BigDecimal monthlyPayment,
        BigDecimal creditLimit,
        String cardTier) {
}
