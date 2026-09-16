package com.bankingplatform.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The authorization rules themselves, isolated from any controller.
 *
 * <p>Every rule is asserted in both directions. A guard that only ever gets
 * tested on the allow path is not a guard.
 */
@DisplayName("Access guard")
class AccessGuardTest {

    private static final CallerIdentity CUSTOMER_A = new CallerIdentity(1L, "a", Role.CUSTOMER);
    private static final CallerIdentity CUSTOMER_B = new CallerIdentity(2L, "b", Role.CUSTOMER);
    private static final CallerIdentity EMPLOYEE = new CallerIdentity(50L, "emp", Role.EMPLOYEE);
    private static final CallerIdentity ADMIN = new CallerIdentity(51L, "adm", Role.ADMIN);

    @Nested
    @DisplayName("owner or staff")
    class OwnerOrStaff {

        @Test
        @DisplayName("the owner is allowed")
        void ownerAllowed() {
            assertThatCode(() -> AccessGuard.requireOwnerOrStaff(CUSTOMER_A, 1L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("another customer is denied")
        void otherCustomerDenied() {
            assertThatThrownBy(() -> AccessGuard.requireOwnerOrStaff(CUSTOMER_A, 2L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("staff are allowed across customers")
        void staffAllowed() {
            assertThatCode(() -> AccessGuard.requireOwnerOrStaff(EMPLOYEE, 1L)).doesNotThrowAnyException();
            assertThatCode(() -> AccessGuard.requireOwnerOrStaff(ADMIN, 2L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null owner never matches a customer")
        void nullOwnerDenied() {
            // Otherwise a resource with no owner recorded would be readable by
            // whichever caller happened to ask for it.
            assertThatThrownBy(() -> AccessGuard.requireOwnerOrStaff(CUSTOMER_A, null))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("self only")
    class SelfOnly {

        @Test
        @DisplayName("staff do not bypass a self-only rule")
        void staffDoNotBypass() {
            assertThatThrownBy(() -> AccessGuard.requireSelf(EMPLOYEE, 1L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("the subject is allowed")
        void selfAllowed() {
            assertThatCode(() -> AccessGuard.requireSelf(CUSTOMER_B, 2L)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("staff and admin roles")
    class Roles {

        @Test
        @DisplayName("a customer is not staff")
        void customerNotStaff() {
            assertThatThrownBy(() -> AccessGuard.requireStaff(CUSTOMER_A))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("an employee is not an admin")
        void employeeNotAdmin() {
            assertThatCode(() -> AccessGuard.requireStaff(EMPLOYEE)).doesNotThrowAnyException();
            assertThatThrownBy(() -> AccessGuard.requireAdmin(EMPLOYEE))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("an admin satisfies both staff and admin")
        void adminSatisfiesBoth() {
            assertThatCode(() -> AccessGuard.requireStaff(ADMIN)).doesNotThrowAnyException();
            assertThatCode(() -> AccessGuard.requireAdmin(ADMIN)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("acting for a target user")
    class TargetUser {

        @Test
        @DisplayName("a customer may act only for themselves")
        void customerSelfOnly() {
            assertThatCode(() -> AccessGuard.requireTargetUserAllowed(CUSTOMER_A, 1L)).doesNotThrowAnyException();
            assertThatThrownBy(() -> AccessGuard.requireTargetUserAllowed(CUSTOMER_A, 2L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a missing target user is denied for a customer")
        void nullTargetDenied() {
            assertThatThrownBy(() -> AccessGuard.requireTargetUserAllowed(CUSTOMER_A, null))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("staff may act for any user")
        void staffAny() {
            assertThatCode(() -> AccessGuard.requireTargetUserAllowed(EMPLOYEE, 999L)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("absent identity fails closed")
    class FailClosed {

        @Test
        @DisplayName("a null caller is rejected as unauthenticated, not merely denied")
        void nullCaller() {
            assertThatThrownBy(() -> AccessGuard.requireOwnerOrStaff(null, 1L))
                    .isInstanceOf(MissingCallerIdentityException.class);
            assertThatThrownBy(() -> AccessGuard.requireStaff(null))
                    .isInstanceOf(MissingCallerIdentityException.class);
        }

        @Test
        @DisplayName("a caller with no role is rejected")
        void roleless() {
            assertThatThrownBy(() -> AccessGuard.requireOwnerOrStaff(new CallerIdentity(1L, "a", null), 1L))
                    .isInstanceOf(MissingCallerIdentityException.class);
        }

        @Test
        @DisplayName("a caller with no id is rejected")
        void idless() {
            assertThatThrownBy(() -> AccessGuard.requireStaff(new CallerIdentity(null, "a", Role.ADMIN)))
                    .isInstanceOf(MissingCallerIdentityException.class);
        }
    }

    @Nested
    @DisplayName("role parsing")
    class RoleParsing {

        @Test
        @DisplayName("plain and ROLE_-prefixed values both parse")
        void prefixTolerated() {
            assertThat(Role.parse("CUSTOMER")).contains(Role.CUSTOMER);
            assertThat(Role.parse("ROLE_ADMIN")).contains(Role.ADMIN);
            assertThat(Role.parse("role_employee")).contains(Role.EMPLOYEE);
        }

        @ParameterizedTest
        @DisplayName("unknown or absent roles yield nothing rather than a default")
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "SUPERUSER", "ROLE_", "ADMINISTRATOR", "CUSTOMER_ADMIN"})
        void unknownYieldsEmpty(String raw) {
            // Defaulting an unrecognised role to anything would be a silent
            // privilege decision.
            assertThat(Role.parse(raw)).isEmpty();
        }
    }
}
