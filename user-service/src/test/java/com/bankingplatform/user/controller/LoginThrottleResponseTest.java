package com.bankingplatform.user.controller;

import com.bankingplatform.user.dto.LoginRequest;
import com.bankingplatform.user.exception.GlobalExceptionHandler;
import com.bankingplatform.user.exception.LoginThrottledException;
import com.bankingplatform.user.exception.ThrottleStoreUnavailableException;
import com.bankingplatform.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a throttled sign-in looks like on the wire.
 *
 * <p>Two things are being pinned. The status and header, so a client can behave
 * properly: {@code 429} with {@code Retry-After}. And the wording, so the
 * response does not become an account-enumeration oracle — a blocked account
 * and an unknown username must read the same, and neither may confirm that the
 * name exists.
 */
@DisplayName("Sign-in throttling — HTTP contract")
class LoginThrottleResponseTest {

    private static final String BODY = """
            {"username":"ada.lovelace","password":"DemoPassword123!"}
            """;

    private UserService userService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a blocked account is 429 with Retry-After")
    void throttledIsTooManyRequests() throws Exception {
        when(userService.login(any(LoginRequest.class))).thenThrow(new LoginThrottledException(742));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "742"))
                .andExpect(jsonPath("$.message").value("Too many sign-in attempts. Try again later."));
    }

    @Test
    @DisplayName("the refusal says nothing about the account")
    void refusalDoesNotEnumerate() throws Exception {
        when(userService.login(any(LoginRequest.class))).thenThrow(new LoginThrottledException(60));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(not(containsString("ada"))))
                .andExpect(jsonPath("$.message").value(not(containsString("locked"))))
                .andExpect(jsonPath("$.message").value(not(containsString("exists"))));
    }

    @Test
    @DisplayName("a wrong password is still a plain 401")
    void wrongPasswordUnchanged() throws Exception {
        when(userService.login(any(LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    @DisplayName("an unreadable attempt store fails closed, with nothing about Redis in the body")
    void storeFailureIsServiceUnavailable() throws Exception {
        when(userService.login(any(LoginRequest.class)))
                .thenThrow(new ThrottleStoreUnavailableException("redis down",
                        new IllegalStateException("connection refused to 10.0.0.5:6379")));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(not(containsString("edis"))))
                .andExpect(jsonPath("$.message").value(not(containsString("6379"))))
                .andExpect(jsonPath("$.token").doesNotExist());
    }
}
