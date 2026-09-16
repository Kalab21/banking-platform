package com.bankingplatform.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The ownership matrix, in one place.
 *
 * <p>The per-service tests prove each endpoint applies a rule. This proves the
 * rules themselves are the ones intended, across all four principals, so the
 * intended policy is readable without tracing it through five services.
 *
 * <p>Resources belong to customer A (id 1). Customer B (id 2) is the other
 * customer, and the employee and admin are staff.
 */
@DisplayName("Ownership matrix")
class OwnershipMatrixTest {

    private static final long OWNER = 1L;

    private static CallerIdentity caller(String who) {
        return switch (who) {
            case "CUSTOMER_A" -> new CallerIdentity(1L, "a", Role.CUSTOMER);
            case "CUSTOMER_B" -> new CallerIdentity(2L, "b", Role.CUSTOMER);
            case "EMPLOYEE" -> new CallerIdentity(50L, "emp", Role.EMPLOYEE);
            case "ADMIN" -> new CallerIdentity(51L, "adm", Role.ADMIN);
            default -> throw new IllegalArgumentException(who);
        };
    }

    /**
     * Resource access: reading or acting on data owned by customer A.
     *
     * <p>Covers account reads, transaction history, money movement, profile
     * reads and KYC — every one of which resolves to
     * {@code requireOwnerOrStaff} against the owning user.
     */
    @ParameterizedTest(name = "{0} -> data owned by customer A: {1}")
    @DisplayName("owner and staff may reach a customer's data; another customer may not")
    @CsvSource({
            "CUSTOMER_A, ALLOW",
            "CUSTOMER_B, DENY",
            "EMPLOYEE,   ALLOW",
            "ADMIN,      ALLOW",
    })
    void resourceAccess(String who, String expected) {
        Throwable thrown = catchThrowable(() -> AccessGuard.requireOwnerOrStaff(caller(who), OWNER));
        assertOutcome(thrown, expected);
    }

    /**
     * Staff-only operations: account freeze and unfreeze, overdraft limits, and
     * platform-wide statistics.
     */
    @ParameterizedTest(name = "{0} -> staff-only operation: {1}")
    @DisplayName("only staff may perform privileged operations")
    @CsvSource({
            "CUSTOMER_A, DENY",
            "CUSTOMER_B, DENY",
            "EMPLOYEE,   ALLOW",
            "ADMIN,      ALLOW",
    })
    void staffOnly(String who, String expected) {
        Throwable thrown = catchThrowable(() -> AccessGuard.requireStaff(caller(who)));
        assertOutcome(thrown, expected);
    }

    /** Admin-only operations, where an employee is not sufficient. */
    @ParameterizedTest(name = "{0} -> admin-only operation: {1}")
    @DisplayName("an employee does not satisfy an admin-only rule")
    @CsvSource({
            "CUSTOMER_A, DENY",
            "EMPLOYEE,   DENY",
            "ADMIN,      ALLOW",
    })
    void adminOnly(String who, String expected) {
        Throwable thrown = catchThrowable(() -> AccessGuard.requireAdmin(caller(who)));
        assertOutcome(thrown, expected);
    }

    /**
     * Acting for a named user: opening an account, or submitting KYC, where the
     * request body carries the target user.
     */
    @ParameterizedTest(name = "{0} -> acting for customer A: {1}")
    @DisplayName("a customer may act only for themselves; staff may act for anyone")
    @CsvSource({
            "CUSTOMER_A, ALLOW",
            "CUSTOMER_B, DENY",
            "EMPLOYEE,   ALLOW",
            "ADMIN,      ALLOW",
    })
    void actingForUser(String who, String expected) {
        Throwable thrown = catchThrowable(() -> AccessGuard.requireTargetUserAllowed(caller(who), OWNER));
        assertOutcome(thrown, expected);
    }

    /**
     * Self-only operations, where staff deliberately do not get a bypass —
     * enrolling two-factor, for instance, is the subject's own action.
     */
    @ParameterizedTest(name = "{0} -> self-only operation on customer A: {1}")
    @DisplayName("staff do not bypass a self-only rule")
    @CsvSource({
            "CUSTOMER_A, ALLOW",
            "CUSTOMER_B, DENY",
            "EMPLOYEE,   DENY",
            "ADMIN,      DENY",
    })
    void selfOnly(String who, String expected) {
        Throwable thrown = catchThrowable(() -> AccessGuard.requireSelf(caller(who), OWNER));
        assertOutcome(thrown, expected);
    }

    @Test
    @DisplayName("every rule rejects an absent caller as unauthenticated")
    void absentCallerFailsClosed() {
        assertThat(catchThrowable(() -> AccessGuard.requireOwnerOrStaff(null, OWNER)))
                .isInstanceOf(MissingCallerIdentityException.class);
        assertThat(catchThrowable(() -> AccessGuard.requireStaff(null)))
                .isInstanceOf(MissingCallerIdentityException.class);
        assertThat(catchThrowable(() -> AccessGuard.requireAdmin(null)))
                .isInstanceOf(MissingCallerIdentityException.class);
        assertThat(catchThrowable(() -> AccessGuard.requireSelf(null, OWNER)))
                .isInstanceOf(MissingCallerIdentityException.class);
        assertThat(catchThrowable(() -> AccessGuard.requireTargetUserAllowed(null, OWNER)))
                .isInstanceOf(MissingCallerIdentityException.class);
    }

    private static void assertOutcome(Throwable thrown, String expected) {
        if ("ALLOW".equals(expected)) {
            assertThat(thrown).as("expected to be permitted").isNull();
        } else {
            assertThat(thrown).as("expected to be denied").isInstanceOf(AccessDeniedException.class);
        }
    }
}
