package com.bankingplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a registration or sign-in attempt.
 *
 * <p>Two shapes are possible. On success the response carries a {@code token}
 * and the identity behind it. When the account has two-factor authentication
 * enabled and no valid code accompanied the request, {@code twoFactorRequired}
 * is {@code true} and <strong>no token is issued</strong> — the caller must
 * repeat the request with a TOTP code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    /** Null when {@code twoFactorRequired} is true — the session is not yet established. */
    private String token;

    @Builder.Default
    private String type = "Bearer";

    /** Identity of the authenticated user, so clients need not decode the token to learn it. */
    private Long userId;

    private String username;
    private String role;
    private long expiresIn;

    /** True when the password was correct but a second factor is still needed. */
    @Builder.Default
    private boolean twoFactorRequired = false;
}
