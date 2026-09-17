package com.bankingplatform.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDate;
import java.time.Period;

public class AdultDateOfBirthValidator implements ConstraintValidator<AdultDateOfBirth, LocalDate> {

    private int minimumAge;
    private int maximumAge;

    @Override
    public void initialize(AdultDateOfBirth constraint) {
        this.minimumAge = constraint.minimumAge();
        this.maximumAge = constraint.maximumAge();
    }

    @Override
    public boolean isValid(LocalDate dateOfBirth, ConstraintValidatorContext context) {
        // A null date is @NotNull's business, not this constraint's; reporting it
        // twice would show the customer two messages for one empty field.
        if (dateOfBirth == null) {
            return true;
        }

        LocalDate today = LocalDate.now();
        if (dateOfBirth.isAfter(today)) {
            return false;
        }

        int age = Period.between(dateOfBirth, today).getYears();
        return age >= minimumAge && age <= maximumAge;
    }
}
