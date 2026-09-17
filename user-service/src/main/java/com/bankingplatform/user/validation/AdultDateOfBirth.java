package com.bankingplatform.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * A date of birth that belongs to an adult who plausibly exists.
 *
 * <p>Three checks in one place: not in the future, at least eighteen years ago,
 * and not absurdly long ago. The browser applies the same rule for a useful
 * message, but the rule lives here because the browser is not a security
 * boundary — a request that skips the form entirely still has to satisfy this.
 */
@Documented
@Constraint(validatedBy = AdultDateOfBirthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface AdultDateOfBirth {

    String message() default "You must be at least 18 years old to open an account";

    int minimumAge() default 18;

    /** Above this, the date is a typo rather than a customer. */
    int maximumAge() default 120;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
