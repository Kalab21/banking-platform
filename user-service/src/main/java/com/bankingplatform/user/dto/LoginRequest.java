package com.bankingplatform.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    /**
     * TOTP code from the user's authenticator app.
     *
     * <p>Optional: only accounts with two-factor authentication enabled need it.
     * Omitting it on such an account returns a {@code twoFactorRequired}
     * response rather than a token.
     */
    @Pattern(regexp = "^$|^\\d{6}$", message = "Authentication code must be 6 digits")
    private String totpCode;
}
