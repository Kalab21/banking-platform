package com.bankingplatform.user.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.user.dto.TwoFactorSetupResponse;
import com.bankingplatform.user.service.TwoFactorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may manage a second factor.
 *
 * <p>The three 2FA endpoints took a {@code userId} request parameter and used
 * it unchecked. Being authenticated is not the same as being the subject: any
 * customer could enrol a new authenticator against another customer's account,
 * or remove theirs, and a staff token could do the same.
 *
 * <p>The rule here is self-only, deliberately stricter than the
 * owner-or-staff rule used for profiles and KYC. These tests assert it at the
 * HTTP boundary and check that a denied call never reaches the service — a 403
 * that still rotated a secret would not be a fix.
 */
@DisplayName("Two-factor authorization")
class TwoFactorAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long EMPLOYEE = 98L;
    private static final long ADMIN = 99L;

    private static final String CODE = "{\"code\":\"123456\"}";

    private TwoFactorService twoFactorService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        twoFactorService = Mockito.mock(TwoFactorService.class);
        when(twoFactorService.generateSecret(anyLong()))
                .thenReturn(new TwoFactorSetupResponse("secret", "otpauth://totp/demo", "scan this"));

        mvc = MockMvcBuilders.standaloneSetup(new TwoFactorController(twoFactorService))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder,
                                                    long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, role);
    }

    @Nested
    @DisplayName("a customer manages their own second factor")
    class OwnFactor {

        @Test
        @DisplayName("setup for themselves reaches the service")
        void ownSetup() throws Exception {
            mvc.perform(as(post("/api/auth/2fa/setup").param("userId", String.valueOf(CUSTOMER_A)),
                            CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());

            verify(twoFactorService).generateSecret(CUSTOMER_A);
        }

        @Test
        @DisplayName("verify for themselves reaches the service")
        void ownVerify() throws Exception {
            mvc.perform(as(post("/api/auth/2fa/verify").param("userId", String.valueOf(CUSTOMER_A)),
                            CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CODE))
                    .andExpect(status().isOk());

            verify(twoFactorService).verifyAndEnable(CUSTOMER_A, "123456");
        }

        @Test
        @DisplayName("disable for themselves reaches the service")
        void ownDisable() throws Exception {
            mvc.perform(as(delete("/api/auth/2fa").param("userId", String.valueOf(CUSTOMER_A)),
                            CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CODE))
                    .andExpect(status().isOk());

            verify(twoFactorService).disable(CUSTOMER_A, "123456");
        }
    }

    @Nested
    @DisplayName("nobody manages someone else's second factor")
    class ForeignFactor {

        @Test
        @DisplayName("a customer cannot enrol an authenticator against another account")
        void foreignSetupDenied() throws Exception {
            mvc.perform(as(post("/api/auth/2fa/setup").param("userId", String.valueOf(CUSTOMER_B)),
                            CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(twoFactorService);
        }

        @Test
        @DisplayName("a customer cannot confirm an enrolment on another account")
        void foreignVerifyDenied() throws Exception {
            mvc.perform(as(post("/api/auth/2fa/verify").param("userId", String.valueOf(CUSTOMER_B)),
                            CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CODE))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(twoFactorService);
        }

        @Test
        @DisplayName("a customer cannot remove another customer's second factor")
        void foreignDisableDenied() throws Exception {
            mvc.perform(as(delete("/api/auth/2fa").param("userId", String.valueOf(CUSTOMER_B)),
                            CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CODE))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(twoFactorService);
        }
    }

    @Nested
    @DisplayName("staff status does not open this door")
    class StaffCannotBypass {

        @Test
        @DisplayName("an employee cannot enrol an authenticator for a customer")
        void employeeSetupDenied() throws Exception {
            mvc.perform(as(post("/api/auth/2fa/setup").param("userId", String.valueOf(CUSTOMER_A)),
                            EMPLOYEE, "EMPLOYEE"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(twoFactorService);
        }

        @Test
        @DisplayName("an admin cannot remove a customer's second factor")
        void adminDisableDenied() throws Exception {
            // The obvious attack this closes: take the second factor off, then
            // sign in with a password reset.
            mvc.perform(as(delete("/api/auth/2fa").param("userId", String.valueOf(CUSTOMER_A)),
                            ADMIN, "ADMIN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CODE))
                    .andExpect(status().isForbidden());

            verify(twoFactorService, never()).disable(anyLong(), anyString());
        }

        @Test
        @DisplayName("an admin may still manage their own")
        void adminOwnAllowed() throws Exception {
            mvc.perform(as(post("/api/auth/2fa/setup").param("userId", String.valueOf(ADMIN)),
                            ADMIN, "ADMIN"))
                    .andExpect(status().isOk());

            verify(twoFactorService).generateSecret(ADMIN);
        }
    }

    @Test
    @DisplayName("a request with no caller identity is refused before the service")
    void missingIdentityDenied() throws Exception {
        mvc.perform(post("/api/auth/2fa/setup").param("userId", String.valueOf(CUSTOMER_A)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(twoFactorService);
    }
}
