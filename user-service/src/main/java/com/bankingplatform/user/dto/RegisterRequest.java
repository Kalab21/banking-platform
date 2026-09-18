package com.bankingplatform.user.dto;

import com.bankingplatform.user.validation.AdultDateOfBirth;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * What a customer supplies when opening an account.
 *
 * <p>Every rule here is enforced again by the console, which is where the
 * customer gets a useful message. The rules live here because that is where they
 * are load-bearing: a request that never went near the form has to satisfy the
 * same constraints.
 *
 * <p>The Social Security number is the exception to everything else on this
 * class. It is write-only and transient: it is read off the request, checked for
 * shape, reduced to its last four digits and dropped. Nothing persists it and
 * nothing returns it.
 */
@Data
public class RegisterRequest {

    // ------------------------------------------------------------ account

    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    @NotBlank
    @Email
    @Size(max = 100)
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

    // ----------------------------------------------------------- personal

    /*
     * Names are bounded and required, and nothing more. No character allowlist:
     * apostrophes, hyphens, spaces and non-Latin scripts are all ordinary parts
     * of real names, and a pattern that "looks reasonable" mostly succeeds at
     * rejecting people.
     */

    @NotBlank
    @Size(max = 50)
    private String firstName;

    /** Optional, because plenty of people do not have one. */
    @Size(max = 50)
    private String middleName;

    @NotBlank
    @Size(max = 50)
    private String lastName;

    @NotNull(message = "Enter your date of birth")
    @AdultDateOfBirth
    private LocalDate dateOfBirth;

    /**
     * Ten digits. The console formats this for reading and normalises it back
     * before sending, so what arrives here is digits and what is stored is
     * digits — a display string in a database column is a parsing problem
     * waiting to happen.
     */
    @NotBlank(message = "Enter your phone number")
    @Pattern(regexp = "^[0-9]{10}$", message = "Enter a 10-digit US phone number")
    private String phone;

    // ------------------------------------------------------------ address

    @NotBlank(message = "Enter your street address")
    @Size(max = 120)
    private String streetAddress;

    @Size(max = 60)
    private String addressLine2;

    @NotBlank(message = "Enter your city")
    @Size(max = 60)
    private String city;

    /** Two-letter USPS abbreviation; normalised to upper case before storage. */
    @NotBlank(message = "Select a state")
    @Pattern(regexp = "^[A-Za-z]{2}$", message = "Select a state")
    private String state;

    @NotBlank(message = "Enter a valid ZIP code")
    @Pattern(regexp = "^[0-9]{5}(-[0-9]{4})?$", message = "Enter a valid 5-digit ZIP code")
    private String postalCode;

    // ----------------------------------------------------------- identity

    /**
     * Write-only. {@code @JsonProperty(access = WRITE_ONLY)} means Jackson will
     * read this from a request body and refuse to write it into any response,
     * so it cannot be echoed back even if some future endpoint serialises this
     * object by mistake.
     *
     * <p>Hyphens are accepted because that is how people type it; the service
     * strips them, keeps the last four digits and discards the rest.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "Enter your Social Security number")
    @Pattern(
            regexp = "^[0-9]{3}-?[0-9]{2}-?[0-9]{4}$",
            message = "Enter a valid 9-digit Social Security number")
    private String ssn;

    /**
     * Kept out of {@code toString()} so the number cannot reach a log through a
     * debug statement, an exception message or a framework that prints the
     * request object.
     */
    @Override
    public String toString() {
        return "RegisterRequest(username=" + username + ", email=" + email + ")";
    }
}
