package com.bankingplatform.user.exception;

import lombok.Getter;

/**
 * Too many failed sign-in attempts against one account.
 *
 * <p>Carries the remaining window so the response can send {@code Retry-After}.
 * It says nothing about whether the account exists: the same refusal is given
 * for a username that has never been registered, because the counter is keyed
 * on what was submitted rather than on anything looked up.
 */
@Getter
public class LoginThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoginThrottledException(long retryAfterSeconds) {
        super("Too many sign-in attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
