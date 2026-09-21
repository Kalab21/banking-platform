package com.bankingplatform.common.security;

import java.util.Optional;

/**
 * Who is acting on the current thread, for code that is too deep to be handed
 * a {@link CallerIdentity}.
 *
 * <p>Controllers receive the caller as a method argument, which is the right
 * shape for authorization: the check happens where the request arrives. Audit
 * writing is different — it happens several layers down, inside the service
 * method that made the change, and threading the caller through every
 * signature to reach it would put an authorization type into the parameter
 * list of methods that do not authorize anything.
 *
 * <p><b>Absence is meaningful and must not be guessed at.</b> A Kafka
 * listener, a scheduled job and a service-to-service call all run with no
 * caller, and that is correct rather than a bug — but it is not the same as a
 * customer action whose identity was lost. {@link #actor()} answers
 * {@code SYSTEM} for the first and names the user for the second, so an audit
 * row can say which it was instead of leaving a null that means either.
 *
 * <p>Set by {@code CallerContextFilter} on the request thread and cleared in a
 * finally. Deliberately not inherited by child threads: work handed to another
 * thread is no longer the request, and an identity that leaked into a
 * scheduled job would attribute the platform's own actions to whoever
 * happened to be online.
 */
public final class CallerContext {

    private static final ThreadLocal<CallerIdentity> CURRENT = new ThreadLocal<>();

    private CallerContext() {
    }

    /** @return the caller on this thread, if this is a request thread at all */
    public static Optional<CallerIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** @return the acting user's id, or empty when the platform itself is acting */
    public static Optional<Long> userId() {
        return current().map(CallerIdentity::userId);
    }

    /**
     * What kind of actor made the current change.
     *
     * @return the caller's role, or {@code SYSTEM} when there is no caller —
     *         which is a statement, not a gap
     */
    public static String actor() {
        return current()
                .map(CallerIdentity::role)
                .map(Enum::name)
                .orElse(SYSTEM);
    }

    /** The platform acting on its own behalf: a scheduled job or a listener. */
    public static final String SYSTEM = "SYSTEM";

    /**
     * Runs work as a given caller, and restores whatever was there before.
     *
     * <p>The only public way to populate the context. {@link #set} is
     * deliberately not: an identity that any code could install is an
     * identity an audit row cannot be trusted to name, and the one thing this
     * class must not become is a place to forge attribution.
     *
     * <p>Restores rather than clears, so a nested call cannot silently strip
     * the caller from the work that surrounds it.
     */
    public static void runAs(CallerIdentity caller, Runnable work) {
        CallerIdentity previous = CURRENT.get();
        CURRENT.set(caller);
        try {
            work.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static void set(CallerIdentity caller) {
        CURRENT.set(caller);
    }

    static void clear() {
        CURRENT.remove();
    }
}
