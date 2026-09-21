package com.bankingplatform.user.service.impl;

import com.bankingplatform.user.security.LoginAttemptService;
import com.bankingplatform.user.config.JwtUtil;
import com.bankingplatform.user.dto.AuthResponse;
import com.bankingplatform.user.dto.LoginRequest;
import com.bankingplatform.user.mapper.UserMapper;
import com.bankingplatform.user.model.Role;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.CustomerIdentityRepository;
import com.bankingplatform.user.repository.UserRepository;
import com.bankingplatform.user.service.TwoFactorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The second factor at sign-in.
 *
 * <p>The contract being pinned: a correct password alone is not a session when
 * the account has 2FA enabled. The service must withhold the token, say so, and
 * only issue one once a valid TOTP code is presented.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Sign-in — two-factor challenge")
class LoginTwoFactorTest {

    private static final long USER_ID = 11L;
    private static final String TOKEN = "issued.jwt.token";

    @Mock private UserRepository userRepository;
    @Mock private CustomerIdentityRepository customerIdentityRepository;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private TwoFactorService twoFactorService;
    @Mock private LoginAttemptService loginAttemptService;

    @InjectMocks private UserServiceImpl userService;

    private static User user(boolean twoFactorEnabled) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("ada");
        user.setPassword("{bcrypt}hash");
        user.setRole(Role.CUSTOMER);
        user.setTwoFactorEnabled(twoFactorEnabled);
        return user;
    }

    private static LoginRequest request(String totpCode) {
        LoginRequest request = new LoginRequest();
        request.setUsername("ada");
        request.setPassword("Password123!");
        request.setTotpCode(totpCode);
        return request;
    }

    private void givenUser(User user) {
        when(userRepository.findByUsername("ada")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(any(), anyLong())).thenReturn(TOKEN);
        when(jwtUtil.getExpiration()).thenReturn(86_400_000L);
    }

    @Nested
    @DisplayName("without two-factor enabled")
    class WithoutTwoFactor {

        @Test
        @DisplayName("a correct password issues a session immediately")
        void issuesTokenDirectly() {
            givenUser(user(false));

            AuthResponse response = userService.login(request(null));

            assertThat(response.getToken()).isEqualTo(TOKEN);
            assertThat(response.isTwoFactorRequired()).isFalse();
            verify(twoFactorService, never()).verifyCode(anyLong(), anyString());
        }

        @Test
        @DisplayName("the response carries the user id, so clients need not decode the token")
        void responseCarriesUserId() {
            givenUser(user(false));

            assertThat(userService.login(request(null)).getUserId()).isEqualTo(USER_ID);
        }
    }

    @Nested
    @DisplayName("with two-factor enabled")
    class WithTwoFactor {

        @Test
        @DisplayName("a correct password alone does not issue a session")
        void passwordAloneIsNotEnough() {
            givenUser(user(true));

            AuthResponse response = userService.login(request(null));

            assertThat(response.isTwoFactorRequired()).isTrue();
            assertThat(response.getToken()).isNull();
            // The challenge must not leak anything beyond who is being challenged.
            assertThat(response.getRole()).isNull();
            verify(jwtUtil, never()).generateToken(any(), anyLong());
        }

        @Test
        @DisplayName("an empty code is treated as no code, not as a valid one")
        void blankCodeIsAChallengeNotAPass() {
            givenUser(user(true));

            AuthResponse response = userService.login(request("   "));

            assertThat(response.isTwoFactorRequired()).isTrue();
            assertThat(response.getToken()).isNull();
            verify(twoFactorService, never()).verifyCode(anyLong(), anyString());
        }

        @Test
        @DisplayName("a wrong code is rejected and still issues no session")
        void wrongCodeIsRejected() {
            givenUser(user(true));
            when(twoFactorService.verifyCode(USER_ID, "000000")).thenReturn(false);

            assertThatThrownBy(() -> userService.login(request("000000")))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Invalid authentication code");

            verify(jwtUtil, never()).generateToken(any(), anyLong());
        }

        @Test
        @DisplayName("a valid code completes sign-in and issues the session")
        void validCodeCompletesSignIn() {
            givenUser(user(true));
            when(twoFactorService.verifyCode(USER_ID, "123456")).thenReturn(true);

            AuthResponse response = userService.login(request("123456"));

            assertThat(response.getToken()).isEqualTo(TOKEN);
            assertThat(response.isTwoFactorRequired()).isFalse();
            assertThat(response.getUserId()).isEqualTo(USER_ID);
            verify(twoFactorService).verifyCode(eq(USER_ID), eq("123456"));
        }
    }
}
