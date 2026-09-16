package com.bankingplatform.common.security;

/**
 * Roles the platform recognises, mirroring the values user-service puts in the
 * JWT and the gateway forwards on {@code X-User-Role}.
 */
public enum Role {

    CUSTOMER,
    EMPLOYEE,
    ADMIN;

    /**
     * Parses a role as it arrives on the wire.
     *
     * <p>Spring Security style prefixes are tolerated because the token carries
     * authorities as {@code ROLE_CUSTOMER}. An unrecognised value yields empty
     * rather than a default, so an unexpected role can never be silently
     * treated as a privileged one.
     */
    public static java.util.Optional<Role> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalised = raw.trim().toUpperCase();
        if (normalised.startsWith("ROLE_")) {
            normalised = normalised.substring("ROLE_".length());
        }
        for (Role role : values()) {
            if (role.name().equals(normalised)) {
                return java.util.Optional.of(role);
            }
        }
        return java.util.Optional.empty();
    }
}
