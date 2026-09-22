package com.bankingplatform.application.underwriting;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the policy concluded, and the figures it concluded it from.
 *
 * <p>The ratios travel with the outcome rather than being recomputed later,
 * because the inputs move: a customer's income is updated, their score changes,
 * their debts are paid down. A decision explained from today's figures is not
 * the decision that was taken.
 *
 * @param outcome        approve, refuse, or send to a human
 * @param reasonCodes    why, in order of weight; never empty
 * @param approvedAmount what may be lent, which may be less than was asked for
 * @param dti            debt-to-income as measured, null where not applicable
 * @param ltv            loan-to-value as measured, null where not applicable
 * @param policyVersion  the policy these thresholds came from
 * @param offeredTerms   what the bank will lend on, null unless approved
 */
public record UnderwritingDecision(
        Outcome outcome,
        List<ReasonCode> reasonCodes,
        BigDecimal approvedAmount,
        BigDecimal dti,
        BigDecimal ltv,
        String policyVersion,
        OfferedTerms offeredTerms) {

    public enum Outcome {
        /** Inside policy on every rule. */
        APPROVE,
        /** Outside policy on a rule that is not a matter of judgement. */
        REJECT,
        /** Inside the hard limits, but a human decides. */
        REFER
    }

    public boolean isApproved() {
        return outcome == Outcome.APPROVE;
    }

    public boolean isRejected() {
        return outcome == Outcome.REJECT;
    }

    public boolean isReferred() {
        return outcome == Outcome.REFER;
    }
}
