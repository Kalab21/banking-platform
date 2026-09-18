package com.bankingplatform.user.controller;

import com.bankingplatform.user.dto.AuthResponse;
import com.bankingplatform.user.exception.DuplicateResourceException;
import com.bankingplatform.user.exception.GlobalExceptionHandler;
import com.bankingplatform.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What registration says back when it goes wrong.
 *
 * <p>An error response is the easiest place for a secret to escape: the default
 * behaviour of a binding failure is to quote the value that failed, which for
 * this endpoint would put a Social Security number in an HTTP body, a browser
 * console and whatever collects front-end errors.
 *
 * <p>So these assert on the whole response body rather than on one field. If a
 * handler is ever changed to include rejected values, these fail.
 */
@DisplayName("Registration error responses")
class RegistrationErrorResponseTest {

    private static final String FULL_SSN = "123-45-6789";
    private static final String SSN_DIGITS = "123456789";
    private static final String PASSWORD = "Northbank2026";

    private UserService userService;
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(json);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    /** A completed wizard as JSON, which individual tests spoil one field of. */
    private static String body(String ssn, String dateOfBirth) {
        return """
                {
                  "username": "avery.sinclair",
                  "email": "avery.sinclair@example.com",
                  "password": "%s",
                  "firstName": "Avery",
                  "lastName": "Sinclair",
                  "dateOfBirth": "%s",
                  "phone": "2405550148",
                  "streetAddress": "123 Example Street",
                  "city": "Silver Spring",
                  "state": "MD",
                  "postalCode": "20910",
                  "ssn": "%s"
                }
                """.formatted(PASSWORD, dateOfBirth, ssn);
    }

    private MvcResult postRegistration(String requestBody, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private static void assertNoSecrets(String responseBody) {
        assertThat(responseBody)
                .doesNotContain(FULL_SSN)
                .doesNotContain(SSN_DIGITS)
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("a rejected Social Security number is not quoted back")
    void malformedSsnNotEchoed() throws Exception {
        MvcResult result = postRegistration(body("123-45-678", "1990-01-15"), 400);

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("9-digit");
        assertThat(responseBody).doesNotContain("123-45-678");
        assertNoSecrets(responseBody);
    }

    @Test
    @DisplayName("a rejected date of birth explains the rule without repeating the entry")
    void underageRejectionSaysWhy() throws Exception {
        MvcResult result = postRegistration(body(FULL_SSN, "2015-01-15"), 400);

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("18");
        assertNoSecrets(responseBody);
    }

    @Test
    @DisplayName("a duplicate username is reported without the rest of the submission")
    void duplicateUsername() throws Exception {
        when(userService.register(any()))
                .thenThrow(new DuplicateResourceException("Username already taken: avery.sinclair"));

        MvcResult result = postRegistration(body(FULL_SSN, "1990-01-15"), 409);

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("already taken");
        assertNoSecrets(responseBody);
    }

    @Test
    @DisplayName("an unexpected failure returns a generic message, not the request")
    void unexpectedFailureIsGeneric() throws Exception {
        // Whatever went wrong, the body the customer sent is not part of the
        // explanation.
        when(userService.register(any()))
                .thenThrow(new IllegalStateException("persistence failed for 123-45-6789"));

        MvcResult result = postRegistration(body(FULL_SSN, "1990-01-15"), 500);

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("An unexpected error occurred");
        assertNoSecrets(responseBody);
    }

    @Test
    @DisplayName("a successful registration returns a session and nothing else")
    void successCarriesNoProfileData() throws Exception {
        when(userService.register(any())).thenReturn(AuthResponse.builder()
                .token("issued.jwt.token")
                .userId(42L)
                .username("avery.sinclair")
                .role("CUSTOMER")
                .expiresIn(86_400_000L)
                .build());

        MvcResult result = postRegistration(body(FULL_SSN, "1990-01-15"), 201);

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("avery.sinclair");
        assertNoSecrets(responseBody);
    }
}
