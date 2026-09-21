package com.bankingplatform.user.service.impl;

import com.bankingplatform.user.config.JwtUtil;
import com.bankingplatform.user.dto.AuthResponse;
import com.bankingplatform.user.dto.LoginRequest;
import com.bankingplatform.user.exception.LoginThrottledException;
import com.bankingplatform.user.exception.ThrottleStoreUnavailableException;
import com.bankingplatform.user.mapper.UserMapper;
import com.bankingplatform.user.model.Role;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.CustomerIdentityRepository;
import com.bankingplatform.user.repository.UserRepository;
import com.bankingplatform.user.security.LoginAttemptService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What counts as a failed sign-in, and what does not.
 *
 * <p>The counting rules are the whole design. Count too much and a customer
 * with two-factor enabled locks themselves out by submitting the password the
 * form asked for; count too little and the limiter can be walked past. These
 * tests pin each case at the service, where the decision is made.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Sign-in — per-account attempt counting")
class LoginThrottleSemanticsTest {

    private static final long USER_ID = 11L;
    private static final String USERNAME = "ada";
    private static final String OTHER = "grace";
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
        user.setUsername(USERNAME);
        user.setPassword("{bcrypt}hash");
        user.setRole(Role.CUSTOMER);
        user.setTwoFactorEnabled(twoFactorEnabled);
        return user;
    }

    private static LoginRequest request(String username, String code) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword("DemoPassword123!");
        request.setTotpCode(code);
        return request;
    }

    private void accountExists(boolean twoFactorEnabled) {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user(twoFactorEnabled)));
        when(jwtUtil.generateToken(any(), anyLong())).thenReturn(TOKEN);
        when(jwtUtil.getExpiration()).thenReturn(86_400_000L);
    }

    @Nested
    @DisplayName("a wrong password")
    class WrongPassword {

        @Test
        @DisplayName("is counted, and the failure is still reported as a failure")
        void countsAndRethrows() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Bad credentials"));

            assertThatThrownBy(() -> userService.login(request(USERNAME, null)))
                    .isInstanceOf(BadCredentialsException.class);

            verify(loginAttemptService).recordFailure(USERNAME);
            verify(jwtUtil, never()).generateToken(any(), anyLong());
        }

        @Test
        @DisplayName("does not clear the count")
        void doesNotClear() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Bad credentials"));

            assertThatThrownBy(() -> userService.login(request(USERNAME, null)))
                    .isInstanceOf(BadCredentialsException.class);

            verify(loginAttemptService, never()).clear(anyString());
        }
    }

    @Nested
    @DisplayName("an account already over the limit")
    class AlreadyThrottled {

        @Test
        @DisplayName("is refused before the password is even checked")
        void refusedBeforeAuthenticating() {
            doThrow(new LoginThrottledException(600))
                    .when(loginAttemptService).assertNotThrottled(USERNAME);

            assertThatThrownBy(() -> userService.login(request(USERNAME, null)))
                    .isInstanceOf(LoginThrottledException.class);

            verify(authenticationManager, never()).authenticate(any());
            verify(jwtUtil, never()).generateToken(any(), anyLong());
        }

        @Test
        @DisplayName("and the refusal is not itself counted as an attempt")
        void refusalNotCounted() {
            doThrow(new LoginThrottledException(600))
                    .when(loginAttemptService).assertNotThrottled(USERNAME);

            assertThatThrownBy(() -> userService.login(request(USERNAME, null)))
                    .isInstanceOf(LoginThrottledException.class);

            // Otherwise a blocked account extends its own block on every retry.
            verify(loginAttemptService, never()).recordFailure(anyString());
        }

        @Test
        @DisplayName("carries the remaining window so the caller can be told when to return")
        void carriesRetryAfter() {
            doThrow(new LoginThrottledException(742))
                    .when(loginAttemptService).assertNotThrottled(USERNAME);

            assertThatThrownBy(() -> userService.login(request(USERNAME, null)))
                    .isInstanceOfSatisfying(LoginThrottledException.class,
                            thrown -> assertThat(thrown.getRetryAfterSeconds()).isEqualTo(742));
        }

        @Test
        @DisplayName("blocks only the username it was recorded against")
        void otherUsernameUnaffected() {
            doThrow(new LoginThrottledException(600))
                    .when(loginAttemptService).assertNotThrottled(USERNAME);
            when(userRepository.findByUsername(OTHER)).thenReturn(Optional.of(user(false)));
            when(jwtUtil.generateToken(any(), anyLong())).thenReturn(TOKEN);
            when(jwtUtil.getExpiration()).thenReturn(86_400_000L);

            AuthResponse response = userService.login(request(OTHER, null));

            assertThat(response.getToken()).isEqualTo(TOKEN);
            verify(loginAttemptService).clear(OTHER);
        }
    }

    @Nested
    @DisplayName("an account with two-factor enabled")
    class SecondFactor {

        @Test
        @DisplayName("a correct password with no code yet is neither a failure nor a success")
        void challengeIsNotAFailure() {
            accountExists(true);

            AuthResponse response = userService.login(request(USERNAME, null));

            assertThat(response.isTwoFactorRequired()).isTrue();
            assertThat(response.getToken()).isNull();
            // Counting this would lock a 2FA customer out for using the form as
            // designed; clearing it would let a guesser reset the counter with
            // a password they already know.
            verify(loginAttemptService, never()).recordFailure(anyString());
            verify(loginAttemptService, never()).clear(anyString());
        }

        @Test
        @DisplayName("a wrong code is counted — it is the second half of a guess")
        void wrongCodeCounts() {
            accountExists(true);
            when(twoFactorService.verifyCode(anyLong(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> userService.login(request(USERNAME, "000000")))
                    .isInstanceOf(BadCredentialsException.class);

            verify(loginAttemptService).recordFailure(USERNAME);
            verify(jwtUtil, never()).generateToken(any(), anyLong());
        }

        @Test
        @DisplayName("a valid code completes the sign-in and clears the count")
        void validCodeClears() {
            accountExists(true);
            when(twoFactorService.verifyCode(anyLong(), anyString())).thenReturn(true);

            AuthResponse response = userService.login(request(USERNAME, "123456"));

            assertThat(response.getToken()).isEqualTo(TOKEN);
            verify(loginAttemptService).clear(USERNAME);
            verify(loginAttemptService, never()).recordFailure(anyString());
        }
    }

    @Nested
    @DisplayName("an account without a second factor")
    class SingleFactor {

        @Test
        @DisplayName("signs in and clears the count")
        void clearsOnSuccess() {
            accountExists(false);

            AuthResponse response = userService.login(request(USERNAME, null));

            assertThat(response.getToken()).isEqualTo(TOKEN);
            verify(loginAttemptService).clear(USERNAME);
        }
    }

    @Test
    @DisplayName("a store that cannot be read issues no token")
    void storeFailureIssuesNoToken() {
        // Fail closed. Treating an unreachable counter as "no attempts" would
        // remove the limiter exactly when it is most needed.
        doThrow(new ThrottleStoreUnavailableException("down", new IllegalStateException()))
                .when(loginAttemptService).assertNotThrottled(USERNAME);

        assertThatThrownBy(() -> userService.login(request(USERNAME, null)))
                .isInstanceOf(ThrottleStoreUnavailableException.class);

        verify(authenticationManager, never()).authenticate(any());
        verify(jwtUtil, never()).generateToken(any(), anyLong());
    }
}
