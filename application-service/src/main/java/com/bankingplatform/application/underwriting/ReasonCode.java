package com.bankingplatform.application.underwriting;

/**
 * Why a decision went the way it did, as something a machine can act on.
 *
 * <p>A decision that explains itself only in prose cannot be counted, filtered
 * or tested. These codes are the record; the customer-facing wording is a
 * translation of them and lives in the console, so changing how a refusal is
 * phrased never changes what was decided.
 *
 * <p>Nothing here names a real bureau, a real lender or a real policy. The
 * thresholds behind these codes are Northbank's demo policy and are configured,
 * not hard-coded.
 */
public enum ReasonCode {

    /** The synthetic score is below the minimum this product asks for. */
    CREDIT_SCORE_BELOW_MINIMUM,

    /** Debt-to-income is above what the policy allows for this product. */
    DTI_ABOVE_POLICY,

    /** Loan-to-value is above what the policy allows for a secured product. */
    LTV_ABOVE_POLICY,

    /** More was asked for than the product lends. */
    REQUEST_AMOUNT_ABOVE_POLICY,

    /** The term asked for is not one this product offers. */
    TERM_NOT_SUPPORTED,

    /**
     * Identity is not established well enough to lend. This is not a refusal:
     * an application whose KYC is merely unfinished is referred, because the
     * customer may yet complete it.
     */
    KYC_REVIEW_REQUIRED,

    /** Identity checks were failed outright rather than left unfinished. */
    KYC_REJECTED,

    /** Inside policy on every rule, but close enough to a limit to be looked at. */
    MANUAL_REVIEW_REQUIRED,

    /**
     * The policy needs a figure the application did not carry. Referring is the
     * honest answer: the request is not refused on its merits, it cannot be
     * judged yet.
     */
    INSUFFICIENT_INFORMATION
}
