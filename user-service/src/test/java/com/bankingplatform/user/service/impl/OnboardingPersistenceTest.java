package com.bankingplatform.user.service.impl;

import com.bankingplatform.user.security.LoginAttemptService;
import com.bankingplatform.user.config.JwtUtil;
import com.bankingplatform.user.dto.RegisterRequest;
import com.bankingplatform.user.dto.UserResponse;
import com.bankingplatform.user.mapper.UserMapper;
import com.bankingplatform.user.mapper.UserMapperImpl;
import com.bankingplatform.user.model.CustomerIdentity;
import com.bankingplatform.user.model.IdentityStatus;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.CustomerIdentityRepository;
import com.bankingplatform.user.repository.UserRepository;
import com.bankingplatform.user.service.TwoFactorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What onboarding writes down, and what it throws away.
 *
 * <p>The profile half is ordinary: the fields the customer filled in have to
 * reach the row, in the canonical shape rather than whatever shape they typed.
 *
 * <p>The identity half is the reason this test exists. The Social Security
 * number is supplied, checked for shape, reduced to four digits and dropped.
 * Nothing stores the rest of it and nothing returns it, and those are claims a
 * reader should not have to take on trust — so these assertions sweep the saved
 * entity and the serialized response for the number rather than checking the
 * one field where it would be most obvious.
 *
 * <p>Every value is synthetic: {@code example.com}, the reserved 555-01xx phone
 * range, and a Social Security number reserved for demonstration use.
 */
@DisplayName("Onboarding persistence")
class OnboardingPersistenceTest {

    private static final String FULL_SSN = "123-45-6789";
    private static final String SSN_DIGITS = "123456789";
    private static final String PLAINTEXT_PASSWORD = "Northbank2026";

    private static final long NEW_USER_ID = 42L;

    private UserRepository userRepository;
    private CustomerIdentityRepository identityRepository;
    private UserServiceImpl userService;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        identityRepository = mock(CustomerIdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);

        // The real generated mapper, not a mock: how a request becomes an
        // entity is part of what is under test here.
        UserMapper userMapper = new UserMapperImpl();

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}$2a$10$storedhash");
        when(jwtUtil.generateToken(any(), anyLong())).thenReturn("issued.jwt.token");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(NEW_USER_ID);
            return user;
        });
        when(identityRepository.save(any(CustomerIdentity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userService = new UserServiceImpl(
                userRepository,
                identityRepository,
                userMapper,
                passwordEncoder,
                jwtUtil,
                mock(AuthenticationManager.class),
                mock(TwoFactorService.class),
                mock(LoginAttemptService.class));
    }

    /** A completed wizard, with the two fields that arrive un-normalised. */
    private static RegisterRequest completedWizard() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("avery.sinclair");
        request.setEmail("avery.sinclair@example.com");
        request.setPassword(PLAINTEXT_PASSWORD);
        request.setFirstName("Avery");
        request.setMiddleName("Quinn");
        request.setLastName("Sinclair");
        request.setDateOfBirth(LocalDate.of(1990, 1, 15));
        request.setPhone("2405550148");
        request.setStreetAddress("123 Example Street");
        request.setAddressLine2("Apt 4B");
        request.setCity("Silver Spring");
        request.setState("md");          // lower case, as a form control may send it
        request.setPostalCode(" 20910 "); // padded, as a paste may leave it
        request.setSsn(FULL_SSN);
        return request;
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    private CustomerIdentity savedIdentity() {
        ArgumentCaptor<CustomerIdentity> captor = ArgumentCaptor.forClass(CustomerIdentity.class);
        verify(identityRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("profile")
    class Profile {

        @Test
        @DisplayName("everything the customer entered reaches the row")
        void profileFieldsPersisted() {
            // The point of the wizard: these are real columns, not decoration
            // that vanishes at submit.
            userService.register(completedWizard());

            assertThat(savedUser())
                    .satisfies(user -> {
                        assertThat(user.getFirstName()).isEqualTo("Avery");
                        assertThat(user.getMiddleName()).isEqualTo("Quinn");
                        assertThat(user.getLastName()).isEqualTo("Sinclair");
                        assertThat(user.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 15));
                        assertThat(user.getStreetAddress()).isEqualTo("123 Example Street");
                        assertThat(user.getAddressLine2()).isEqualTo("Apt 4B");
                        assertThat(user.getCity()).isEqualTo("Silver Spring");
                    });
        }

        @Test
        @DisplayName("the state is stored as an upper-case code")
        void stateNormalised() {
            userService.register(completedWizard());

            assertThat(savedUser().getState()).isEqualTo("MD");
        }

        @Test
        @DisplayName("the phone number is stored as digits, and the ZIP without padding")
        void phoneAndZipNormalised() {
            // A display string in a database column is a parsing problem
            // waiting to happen, so the column holds the canonical value.
            RegisterRequest request = completedWizard();
            request.setPhone("2405550148");

            userService.register(request);

            assertThat(savedUser().getPhone()).isEqualTo("2405550148");
            assertThat(savedUser().getPostalCode()).isEqualTo("20910");
        }

        @Test
        @DisplayName("the password is stored hashed, never as typed")
        void passwordHashed() {
            userService.register(completedWizard());

            assertThat(savedUser().getPassword())
                    .isNotEqualTo(PLAINTEXT_PASSWORD)
                    .startsWith("{bcrypt}");
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("only the last four digits are written down")
        void onlyLastFourPersisted() {
            userService.register(completedWizard());

            CustomerIdentity identity = savedIdentity();
            assertThat(identity.getSsnLast4()).isEqualTo("6789");
            assertThat(identity.getUserId()).isEqualTo(NEW_USER_ID);
        }

        @Test
        @DisplayName("hyphens in the entered number make no difference")
        void hyphensIrrelevant() {
            RegisterRequest request = completedWizard();
            request.setSsn(SSN_DIGITS);

            userService.register(request);

            assertThat(savedIdentity().getSsnLast4()).isEqualTo("6789");
        }

        @Test
        @DisplayName("the status is SUBMITTED, because nothing has verified anything")
        void statusIsSubmittedNotVerified() {
            // There is no identity-verification provider behind this system.
            // Passing a format check is not verification, so no state here may
            // claim otherwise.
            userService.register(completedWizard());

            assertThat(savedIdentity().getStatus()).isEqualTo(IdentityStatus.SUBMITTED);
            assertThat(IdentityStatus.values()).containsExactly(IdentityStatus.SUBMITTED);
        }

        @Test
        @DisplayName("no field of the saved user holds the number, in any form")
        void fullSsnNowhereOnTheUserRow() throws Exception {
            userService.register(completedWizard());

            User user = savedUser();
            for (Field field : User.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(user);
                if (value != null) {
                    assertThat(String.valueOf(value))
                            .as("User.%s", field.getName())
                            .doesNotContain(SSN_DIGITS)
                            .doesNotContain(FULL_SSN)
                            .doesNotContain(PLAINTEXT_PASSWORD);
                }
            }
        }

        @Test
        @DisplayName("the identity record holds four digits and nothing derived from the rest")
        void identityRecordCarriesNothingElse() throws Exception {
            // Deliberately no digest of the full number either: a Social
            // Security number has fewer than a billion possible values, so an
            // unkeyed hash of one is recoverable by exhaustive search and would
            // be a false reassurance rather than a protection.
            userService.register(completedWizard());

            CustomerIdentity identity = savedIdentity();
            for (Field field : CustomerIdentity.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(identity);
                if (value != null) {
                    assertThat(String.valueOf(value))
                            .as("CustomerIdentity.%s", field.getName())
                            .doesNotContain(SSN_DIGITS)
                            .doesNotContain(FULL_SSN);
                }
            }
            assertThat(identity.getSsnLast4()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("what comes back")
    class Response {

        @Test
        @DisplayName("a profile read carries the masked digits and the honest status")
        void profileReadShowsLastFourAndStatus() {
            User stored = storedUser();
            when(userRepository.findById(NEW_USER_ID)).thenReturn(Optional.of(stored));
            when(identityRepository.findByUserId(NEW_USER_ID)).thenReturn(Optional.of(
                    CustomerIdentity.builder()
                            .userId(NEW_USER_ID)
                            .ssnLast4("6789")
                            .status(IdentityStatus.SUBMITTED)
                            .build()));

            UserResponse response = userService.getUserById(NEW_USER_ID);

            assertThat(response.getSsnLast4()).isEqualTo("6789");
            assertThat(response.getIdentityStatus()).isEqualTo("SUBMITTED");
            assertThat(response.getIdentityStatus()).isNotEqualTo("VERIFIED");
        }

        @Test
        @DisplayName("an account created before onboarding existed still reads back")
        void legacyAccountWithoutIdentityRecord() {
            // The profile columns are nullable precisely so these rows survive.
            // A missing identity record is an absent field, not a failed read.
            User legacy = storedUser();
            legacy.setDateOfBirth(null);
            legacy.setStreetAddress(null);
            legacy.setState(null);
            when(userRepository.findById(NEW_USER_ID)).thenReturn(Optional.of(legacy));
            when(identityRepository.findByUserId(NEW_USER_ID)).thenReturn(Optional.empty());

            UserResponse response = userService.getUserById(NEW_USER_ID);

            assertThat(response.getUsername()).isEqualTo("avery.sinclair");
            assertThat(response.getSsnLast4()).isNull();
            assertThat(response.getIdentityStatus()).isNull();
        }

        @Test
        @DisplayName("the serialized response contains no secret of any kind")
        void responseJsonCarriesNoSecrets() throws Exception {
            when(userRepository.findById(NEW_USER_ID)).thenReturn(Optional.of(storedUser()));
            when(identityRepository.findByUserId(NEW_USER_ID)).thenReturn(Optional.of(
                    CustomerIdentity.builder()
                            .userId(NEW_USER_ID)
                            .ssnLast4("6789")
                            .status(IdentityStatus.SUBMITTED)
                            .build()));

            String body = json.writeValueAsString(userService.getUserById(NEW_USER_ID));

            assertThat(body)
                    .doesNotContain(SSN_DIGITS)
                    .doesNotContain(FULL_SSN)
                    .doesNotContain(PLAINTEXT_PASSWORD)
                    .doesNotContain("{bcrypt}")
                    .doesNotContain("twoFactorSecret")
                    .doesNotContain("password");
            assertThat(body).contains("\"ssnLast4\":\"6789\"");
        }

        @Test
        @DisplayName("the request object itself refuses to serialise the number")
        void requestNeverSerialisesTheSsn() throws Exception {
            // Write-only on the way in. If some future endpoint echoed a
            // RegisterRequest back by mistake, the number would not be in it.
            String body = json.writeValueAsString(completedWizard());

            assertThat(body)
                    .doesNotContain(SSN_DIGITS)
                    .doesNotContain(FULL_SSN)
                    .doesNotContain("ssn");
        }

        private User storedUser() {
            User user = new User();
            user.setId(NEW_USER_ID);
            user.setUsername("avery.sinclair");
            user.setEmail("avery.sinclair@example.com");
            user.setPassword("{bcrypt}$2a$10$storedhash");
            user.setFirstName("Avery");
            user.setLastName("Sinclair");
            user.setDateOfBirth(LocalDate.of(1990, 1, 15));
            user.setPhone("2405550148");
            user.setStreetAddress("123 Example Street");
            user.setCity("Silver Spring");
            user.setState("MD");
            user.setPostalCode("20910");
            user.setTwoFactorSecret("JBSWY3DPEHPK3PXP");
            return user;
        }
    }
}
