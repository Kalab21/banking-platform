package com.bankingplatform.user.security;

/**
 * Per-account sign-in throttling.
 *
 * <p>The gateway already limits requests per client IP, which stops one machine
 * hammering the platform. It does nothing about the opposite shape of attack:
 * many machines, a few attempts each, all against one username. This counts
 * failures against the account rather than the caller.
 *
 * <p>The three methods are deliberately separate. Checking is not the same as
 * counting — a request that is refused before authentication must not add to
 * the total that refused it, or a blocked account could never unblock.
 */
public interface LoginAttemptService {

    /**
     * Refuses the attempt if this username is currently over the limit.
     *
     * @throws com.bankingplatform.user.exception.LoginThrottledException when blocked
     * @throws com.bankingplatform.user.exception.ThrottleStoreUnavailableException
     *         when the store cannot be evaluated — sign-in then fails closed
     */
    void assertNotThrottled(String username);

    /** Records one failed credential attempt and starts the window if needed. */
    void recordFailure(String username);

    /** Forgets the failures for this username, after a complete authentication. */
    void clear(String username);
}
