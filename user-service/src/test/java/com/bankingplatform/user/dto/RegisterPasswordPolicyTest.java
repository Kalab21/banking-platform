package com.bankingplatform.user.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registration password policy.
 *
 * <p>The rule the browser shows and the rule the server enforces have to be the
 * same rule. They were not: the form required eight characters while the DTO
 * accepted six with no composition requirement, so the weaker of the two was
 * the one that actually applied.
 *
 * <p>These pin the server side of that agreement. The console renders the same
 * three conditions as a checklist.
 */
@DisplayName("Registration password policy")
class RegisterPasswordPolicyTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    private static RegisterRequest requestWith(String password) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testperson");
        request.setEmail("test.person@example.com");
        request.setFirstName("Test");
        request.setLastName("Person");
        request.setPassword(password);
        return request;
    }

    private static Set<ConstraintViolation<RegisterRequest>> violationsFor(String password) {
        return validator.validate(requestWith(password));
    }

    @ParameterizedTest
    @DisplayName("a password meeting all three rules is accepted")
    @ValueSource(strings = {
            "Password123",        // the minimum shape
            "DemoPassword123!",   // what the seed script issues
            "Test1234!",          // what the live end-to-end script uses
            "Correct9Horse",
    })
    void acceptsCompliantPasswords(String password) {
        assertThat(violationsFor(password)).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("a password failing any single rule is rejected")
    @ValueSource(strings = {
            "Pass12",        // seven characters or fewer
            "Passw1",        // the old six-character minimum
            "password123",   // no uppercase
            "PASSWORD123",   // no lowercase
            "PasswordOnly",  // no digit
    })
    void rejectsNonCompliantPasswords(String password) {
        assertThat(violationsFor(password))
                .as("violations for %s", password)
                .isNotEmpty();
    }

    @Test
    @DisplayName("the old six-character minimum no longer passes")
    void sixCharactersIsNoLongerEnough() {
        // The previous contract accepted this. Naming it explicitly so the
        // change is visible if anyone relaxes the constraint again.
        assertThat(violationsFor("Abc123")).isNotEmpty();
    }

    @Test
    @DisplayName("a blank password is rejected before the composition rules run")
    void blankRejected() {
        assertThat(violationsFor("   ")).isNotEmpty();
    }
}
