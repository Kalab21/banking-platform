package com.bankingplatform.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    @NotBlank
    @Email
    private String email;

    /**
     * Three deterministic rules, and the console shows exactly these three as a
     * checklist while the customer types.
     *
     * <p>The minimum was six characters with no composition requirement, which
     * the browser form had already been contradicting with its own minimum of
     * eight — two rules for one field, and the weaker one was the one that
     * actually applied. Six characters with no other constraint is inside the
     * range an online guessing attack reaches.
     *
     * <p>Deliberately not a fourth rule for symbols: every additional class
     * pushes people towards one predictable substitution, and the length floor
     * does more for the same friction. This is the registration contract only;
     * it does not re-validate or invalidate a password already stored.
     */
    @NotBlank
    @Size(min = 8, max = 100)
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9]).+$",
            message = "Password must include an uppercase letter, a lowercase letter and a number")
    private String password;

    @NotBlank
    @Size(max = 50)
    private String firstName;

    @NotBlank
    @Size(max = 50)
    private String lastName;

    @Size(max = 20)
    private String phone;
}
