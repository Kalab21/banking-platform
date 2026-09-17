package com.bankingplatform.transaction.model;

/**
 * The lifecycle of one idempotent money-movement attempt.
 *
 * <p>The distinction that matters is between a failure that is known to have
 * moved no money and a failure whose effect on the balance is unknown. The
 * first is safe to run again; the second is not, and no amount of convenience
 * justifies guessing.
 */
public enum IdempotencyStatus {

    /**
     * Claimed, and the operation is running. A second request carrying the same
     * key waits briefly for a verdict rather than starting its own attempt.
     */
    IN_PROGRESS,

    /**
     * The money moved and the result was persisted. A replay returns the stored
     * response and executes nothing.
     */
    COMPLETED,

    /**
     * Rejected before anything was applied — validation, insufficient funds, a
     * frozen account, or a circuit that was open so the call never left this
     * service. Nothing moved, so the key may be claimed again and the request
     * retried; a deterministic failure is not cached as though it were an
     * answer.
     */
    FAILED,

    /**
     * The attempt reached the account service and then lost the thread: a
     * timeout, a 5xx, a transfer whose debit applied but whose credit did not,
     * or a local failure after the remote balance had already changed.
     *
     * <p>Terminal on purpose. Whether the balance changed is not knowable from
     * here, and retrying under the same key would be a coin-flip between a
     * no-op and a second debit. The key stays spent and the row is left for
     * reconciliation.
     */
    UNKNOWN
}
