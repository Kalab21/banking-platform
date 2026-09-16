package com.bankingplatform.common.security;

/**
 * The caller of a user-facing request, as established by the API gateway.
 *
 * <p>This is <em>not</em> authentication. The gateway validates the JWT
 * signature and expiry, then forwards the identity it derived on
 * {@code X-User-Id}, {@code X-Username} and {@code X-User-Role}. Services
 * consume that identity; they do not re-verify the token.
 *
 * <p>The security of that arrangement rests on two things, both enforced
 * elsewhere and both required:
 * <ol>
 *   <li>The gateway overwrites these headers on every routed request, so a
 *       client cannot supply its own (see {@code JwtAuthenticationFilter}).</li>
 *   <li>The business services are not reachable from outside the Compose
 *       network, so the gateway cannot be bypassed.</li>
 * </ol>
 *
 * <p>Endpoints that take a {@code CallerIdentity} parameter are user-facing by
 * definition. Service-to-service endpoints must not accept one.
 */
public record CallerIdentity(Long userId, String username, Role role) {

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }

    /** Employee or admin — the roles that may act on another customer's data. */
    public boolean isStaff() {
        return role == Role.EMPLOYEE || role == Role.ADMIN;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** True when this caller is the user identified by {@code targetUserId}. */
    public boolean isSelf(Long targetUserId) {
        return targetUserId != null && targetUserId.equals(userId);
    }
}
