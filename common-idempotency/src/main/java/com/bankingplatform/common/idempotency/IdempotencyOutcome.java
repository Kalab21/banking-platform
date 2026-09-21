package com.bankingplatform.common.idempotency;

/**
 * A read-only view of one idempotency record.
 *
 * <p>Deliberately not the entity. A duplicate request polls for the original's
 * verdict, and a JPA query for an entity already in the persistence context
 * returns the instance that is there rather than the state the database now
 * holds. With {@code open-in-view} enabled the persistence context spans the
 * whole request, so every poll returned the same stale {@code IN_PROGRESS} it
 * had read the first time, and a duplicate that should have collected the
 * original result waited out its whole budget and was told to retry instead.
 *
 * <p>A constructor projection is built from the result set, so each read sees
 * what is committed.
 */
public record IdempotencyOutcome(
        Long id,
        String idempotencyKey,
        String operation,
        String requestHash,
        IdempotencyStatus status,
        Integer responseStatus,
        String responseBody,
        String resultRef) {
}
