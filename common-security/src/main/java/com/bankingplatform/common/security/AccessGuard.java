package com.bankingplatform.common.security;

/**
 * The authorization rules applied to user-facing endpoints.
 *
 * <p>Every rule is a named method rather than an inline role comparison, so the
 * intent of a check is visible at the call site and the rules can be tested on
 * their own. All of them throw rather than return a boolean: a caller that
 * forgets to branch on a boolean fails open, and this is the one place where
 * that mistake must be impossible to make.
 */
public final class AccessGuard {

    private AccessGuard() {
    }

    /**
     * The caller may act on data belonging to {@code ownerUserId}.
     *
     * <p>Customers may only reach their own; employees and admins may reach
     * anyone's, which is what the staff workflows in the console require.
     */
    public static void requireOwnerOrStaff(CallerIdentity caller, Long ownerUserId) {
        requireCaller(caller);
        if (caller.isStaff()) {
            return;
        }
        if (!caller.isSelf(ownerUserId)) {
            throw new AccessDeniedException("Not permitted to access this resource");
        }
    }

    /** The caller is acting on their own data, and staff status does not apply. */
    public static void requireSelf(CallerIdentity caller, Long ownerUserId) {
        requireCaller(caller);
        if (!caller.isSelf(ownerUserId)) {
            throw new AccessDeniedException("Not permitted to access this resource");
        }
    }

    /** Employee or admin only — staff operations on the platform or on customers. */
    public static void requireStaff(CallerIdentity caller) {
        requireCaller(caller);
        if (!caller.isStaff()) {
            throw new AccessDeniedException("Staff role required");
        }
    }

    /** Admin only. */
    public static void requireAdmin(CallerIdentity caller) {
        requireCaller(caller);
        if (!caller.isAdmin()) {
            throw new AccessDeniedException("Admin role required");
        }
    }

    /**
     * A customer may only create or act on a record for themselves; staff may
     * name any user.
     *
     * <p>Used where a request body carries a {@code userId}. The body is caller
     * input, so it is checked against the identity the gateway established
     * rather than trusted.
     */
    public static void requireTargetUserAllowed(CallerIdentity caller, Long targetUserId) {
        requireCaller(caller);
        if (caller.isStaff()) {
            return;
        }
        if (targetUserId == null || !caller.isSelf(targetUserId)) {
            throw new AccessDeniedException("Not permitted to act for another user");
        }
    }

    private static void requireCaller(CallerIdentity caller) {
        if (caller == null || caller.role() == null || caller.userId() == null) {
            throw new MissingCallerIdentityException("No caller identity on this request");
        }
    }
}
