package com.bankingplatform.user.exception;

/**
 * The sign-in attempt store could not be reached or evaluated.
 *
 * <p>Thrown rather than swallowed. Treating an unreachable counter as "no
 * attempts recorded" would turn an infrastructure outage into an open door for
 * password guessing, which is exactly when a limiter matters most. Sign-in
 * fails closed with a generic 503 instead, and no token is issued.
 */
public class ThrottleStoreUnavailableException extends RuntimeException {

    public ThrottleStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
