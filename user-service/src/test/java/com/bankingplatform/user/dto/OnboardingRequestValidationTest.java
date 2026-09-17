package com.bankingplatform.user.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The onboarding contract, enforced on the server.
 *
 * <p>The console applies the same rules so the customer gets a useful message
 * where they are typing. These exist because the console is not a security
 * boundary: a request that never went near a browser has to satisfy the same
 * constraints, and these fail if it does not.
 *
 * <p>All values are obviously synthetic — {@code example.com}, the reserved
 * {@code 555-01xx} phone range, and a test Social Security number.
 */
@DisplayName("Onboarding request validation")
class OnboardingRequestValidationTest {

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

    /** A request that satisfies every rule; individual tests spoil one field. */
    private static RegisterRequest valid() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("avery.sinclair");
        request.setEmail("avery.sinclair@example.com");
        request.setPassword("Northbank2026");
        request.setFirstName("Avery");
        request.setLastName("Sinclair");
        request.setDateOfBirth(LocalDate.of(1990, 1, 15));
        request.setPhone("2405550148");
        request.setStreetAddress("123 Example Street");
        request.setCity("Silver Spring");
        request.setState("MD");
        request.setPostalCode("20910");
        request.setSsn("123-45-6789");
        return request;
    }

    private static Set<String> fieldsInViolation(RegisterRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static String messageFor(RegisterRequest request, String field) {
        return validator.validate(request).stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("");
    }

    @Test
    @DisplayName("a complete request is accepted")
    void completeRequestAccepted() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Nested
    @DisplayName("date of birth")
    class DateOfBirth {

        @Test
        @DisplayName("is required")
        void required() {
            RegisterRequest request = valid();
            request.setDateOfBirth(null);

            assertThat(fieldsInViolation(request)).contains("dateOfBirth");
        }

        @Test
        @DisplayName("cannot be in the future")
        void notInTheFuture() {
            RegisterRequest request = valid();
            request.setDateOfBirth(LocalDate.now().plusDays(1));

            assertThat(fieldsInViolation(request)).contains("dateOfBirth");
        }

        @Test
        @DisplayName("must be at least eighteen years ago")
        void mustBeAnAdult() {
            RegisterRequest request = valid();
            // A day short of the eighteenth birthday.
            request.setDateOfBirth(LocalDate.now().minusYears(18).plusDays(1));

            assertThat(messageFor(request, "dateOfBirth")).contains("18");
        }

        @Test
        @DisplayName("accepts someone who turned eighteen today")
        void exactlyEighteenAccepted() {
            RegisterRequest request = valid();
            request.setDateOfBirth(LocalDate.now().minusYears(18));

            assertThat(fieldsInViolation(request)).doesNotContain("dateOfBirth");
        }

        @Test
        @DisplayName("rejects a date too far back to be a customer")
        void absurdlyOldRejected() {
            RegisterRequest request = valid();
            request.setDateOfBirth(LocalDate.now().minusYears(130));

            assertThat(fieldsInViolation(request)).contains("dateOfBirth");
        }
    }

    @Nested
    @DisplayName("address")
    class Address {

        @ParameterizedTest
        @DisplayName("street, city, state and ZIP are all required")
        @ValueSource(strings = {"streetAddress", "city", "state", "postalCode"})
        void requiredFields(String field) {
            RegisterRequest request = valid();
            switch (field) {
                case "streetAddress" -> request.setStreetAddress("  ");
                case "city" -> request.setCity("");
                case "state" -> request.setState(null);
                case "postalCode" -> request.setPostalCode("");
                default -> throw new IllegalArgumentException(field);
            }

            assertThat(fieldsInViolation(request)).contains(field);
        }

        @Test
        @DisplayName("the apartment line is optional")
        void addressLineTwoOptional() {
            RegisterRequest request = valid();
            request.setAddressLine2(null);

            assertThat(validator.validate(request)).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("a state that is not a two-letter code is rejected")
        @ValueSource(strings = {"Maryland", "M", "MDX", "12"})
        void invalidState(String state) {
            RegisterRequest request = valid();
            request.setState(state);

            assertThat(fieldsInViolation(request)).contains("state");
        }

        @Test
        @DisplayName("a lower-case state code is accepted and normalised later")
        void lowerCaseStateAccepted() {
            RegisterRequest request = valid();
            request.setState("md");

            assertThat(fieldsInViolation(request)).doesNotContain("state");
        }

        @ParameterizedTest
        @DisplayName("a malformed ZIP is rejected")
        @ValueSource(strings = {"2091", "209100", "ABCDE", "20910-", "20910-12"})
        void invalidZip(String zip) {
            RegisterRequest request = valid();
            request.setPostalCode(zip);

            assertThat(messageFor(request, "postalCode")).contains("ZIP");
        }

        @ParameterizedTest
        @DisplayName("five-digit and nine-digit ZIPs are both accepted")
        @ValueSource(strings = {"20910", "20910-1234"})
        void validZip(String zip) {
            RegisterRequest request = valid();
            request.setPostalCode(zip);

            assertThat(fieldsInViolation(request)).doesNotContain("postalCode");
        }
    }

    @Nested
    @DisplayName("phone")
    class Phone {

        @Test
        @DisplayName("is required")
        void required() {
            RegisterRequest request = valid();
            request.setPhone(null);

            assertThat(fieldsInViolation(request)).contains("phone");
        }

        @ParameterizedTest
        @DisplayName("must be ten digits, not a formatted string")
        @ValueSource(strings = {"(240) 555-0148", "240-555-0148", "24055501", "240555014812"})
        void mustBeDigits(String phone) {
            // The console formats for reading and normalises before sending, so
            // anything arriving with punctuation did not come from the console.
            RegisterRequest request = valid();
            request.setPhone(phone);

            assertThat(fieldsInViolation(request)).contains("phone");
        }
    }

    @Nested
    @DisplayName("names")
    class Names {

        @Test
        @DisplayName("first and last are required, middle is not")
        void requiredness() {
            RegisterRequest request = valid();
            request.setMiddleName(null);
            assertThat(validator.validate(request)).isEmpty();

            request.setFirstName("  ");
            assertThat(fieldsInViolation(request)).contains("firstName");
        }

        @ParameterizedTest
        @DisplayName("names outside the Latin alphabet are accepted")
        @ValueSource(strings = {"Ada", "O'Neill", "Al-Rashid", "Ní Bhriain", "María José", "李"})
        void noCharacterAllowlist(String name) {
            // A pattern that "looks reasonable" mostly succeeds at rejecting
            // people, so there is not one.
            RegisterRequest request = valid();
            request.setFirstName(name);
            request.setLastName(name);

            assertThat(validator.validate(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Social Security number")
    class Ssn {

        @ParameterizedTest
        @DisplayName("is required")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void required(String ssn) {
            RegisterRequest request = valid();
            request.setSsn(ssn);

            assertThat(fieldsInViolation(request)).contains("ssn");
        }

        @ParameterizedTest
        @DisplayName("must be nine digits")
        @ValueSource(strings = {"12345678", "1234567890", "123-45-678", "abc-de-fghi", "123 45 6789"})
        void mustBeNineDigits(String ssn) {
            RegisterRequest request = valid();
            request.setSsn(ssn);

            assertThat(messageFor(request, "ssn")).contains("9-digit");
        }

        @ParameterizedTest
        @DisplayName("is accepted with or without hyphens")
        @ValueSource(strings = {"123456789", "123-45-6789"})
        void hyphensOptional(String ssn) {
            RegisterRequest request = valid();
            request.setSsn(ssn);

            assertThat(fieldsInViolation(request)).doesNotContain("ssn");
        }

        @Test
        @DisplayName("never appears in the request's own toString")
        void notInToString() {
            // Guards against the number reaching a log through a debug
            // statement, an exception message or a framework that prints the
            // request object.
            RegisterRequest request = valid();

            assertThat(request.toString())
                    .doesNotContain("123-45-6789")
                    .doesNotContain("123456789")
                    .doesNotContain("Northbank2026");
        }
    }

    @Test
    @DisplayName("the password rules from the previous contract still apply")
    void passwordRulesIntact() {
        RegisterRequest request = valid();
        request.setPassword("abc123");

        assertThat(fieldsInViolation(request)).contains("password");
    }
}
