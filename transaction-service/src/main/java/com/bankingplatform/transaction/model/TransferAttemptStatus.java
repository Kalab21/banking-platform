package com.bankingplatform.transaction.model;

/**
 * How far a transfer got, and whether anyone still has to look at it.
 *
 * <p>The distinction that matters is between a transfer that failed having
 * moved nothing and one that failed having moved half. The first is an
 * ordinary rejection; the second is money that has left one account and
 * arrived nowhere.
 */
public enum TransferAttemptStatus {

    /** Recorded, nothing applied yet. A row left here means the process died between. */
    STARTED,

    /** The source was debited. The credit has not been attempted or has not answered. */
    DEBITED,

    /** Both legs applied. Nothing to do. */
    COMPLETED,

    /**
     * The debit applied and the credit did not.
     *
     * <p>The case this whole table exists for. Not an error to be retried
     * blindly: the source is already short, so a naive retry debits it again.
     */
    CREDIT_FAILED,

    /**
     * Reconciliation established what actually happened to each leg and
     * recorded it. What to do about it is a separate decision, deliberately
     * left to a person.
     */
    RECONCILED
}
