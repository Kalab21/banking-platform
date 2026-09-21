package com.bankingplatform.common.idempotency;

import org.springframework.http.HttpStatus;

/**
 * A money-movement request that the idempotency rules refuse to run.
 *
 * <p>Each case carries the status it must produce, because the statuses are
 * part of the endpoint's contract rather than an implementation detail: the
 * client has to be able to tell "you sent this wrong" from "this key is already
 * spent" from "we do not know what happened last time".
 */
public class IdempotencyException extends RuntimeException {

    private final HttpStatus status;
    private final Integer retryAfterSeconds;

    private IdempotencyException(HttpStatus status, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** No key on an endpoint that requires one. */
    public static IdempotencyException missingKey(String header) {
        return new IdempotencyException(HttpStatus.BAD_REQUEST,
                header + " is required on money-movement requests. Generate one opaque value per "
                        + "logical operation and reuse it when retrying that same operation.", null);
    }

    /** A key that is too short, too long, or outside the permitted alphabet. */
    public static IdempotencyException malformedKey(String message) {
        return new IdempotencyException(HttpStatus.BAD_REQUEST, message, null);
    }

    /**
     * The key has been used for a different request. Returning the earlier
     * result would answer a question the client did not ask, and executing this
     * one would break the promise the key makes.
     */
    public static IdempotencyException differentRequest(String header) {
        return new IdempotencyException(HttpStatus.CONFLICT,
                "This " + header + " was already used for a different request. Nothing was executed. "
                        + "Use a new key for a new operation.", null);
    }

    /** A duplicate arrived while the original was still running and did not settle in time. */
    public static IdempotencyException stillRunning(int retryAfterSeconds) {
        return new IdempotencyException(HttpStatus.CONFLICT,
                "An earlier request with this Idempotency-Key is still being processed. "
                        + "This request was not executed. Retry with the same key to collect the result.",
                retryAfterSeconds);
    }

    /**
     * 504 rather than a replayed result, because there is no result to replay.
     *
     * <p>The earlier attempt reached the account service and the outcome was
     * never established. Re-running it could debit twice; reporting success
     * could report money that never moved. Both are worse than saying so.
     */
    public static IdempotencyException outcomeUnknown() {
        return new IdempotencyException(HttpStatus.GATEWAY_TIMEOUT,
                "An earlier request with this Idempotency-Key did not complete with a known outcome, "
                        + "and has not been re-executed. Check the account balance and history before "
                        + "issuing a replacement request under a new key.", null);
    }
}
