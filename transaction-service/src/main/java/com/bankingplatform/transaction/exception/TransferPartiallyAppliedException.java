package com.bankingplatform.transaction.exception;

/**
 * A transfer whose debit succeeded and whose credit did not.
 *
 * <p>The two legs are separate calls to a separate service with its own
 * database. {@code @Transactional} on the transfer method covers this service's
 * rows and nothing else, so a rollback here removes the local transaction
 * records while leaving the source account debited.
 *
 * <p>Repairing that automatically means a compensating credit, which is a saga
 * with its own failure modes and is not implemented. What this exception buys
 * is honesty: the caller is told the transfer is in an inconsistent state
 * rather than being handed the credit leg's error as though the debit had never
 * happened, and the idempotency record is resolved as
 * {@link com.bankingplatform.common.idempotency.IdempotencyStatus#UNKNOWN} so a
 * retry cannot debit the source a second time.
 */
public class TransferPartiallyAppliedException extends RuntimeException {

    public TransferPartiallyAppliedException(String message, Throwable cause) {
        super(message, cause);
    }
}
