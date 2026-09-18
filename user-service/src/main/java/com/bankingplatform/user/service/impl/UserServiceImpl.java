package com.bankingplatform.user.service.impl;

import com.bankingplatform.user.config.JwtUtil;
import com.bankingplatform.user.model.CustomerIdentity;
import com.bankingplatform.user.model.IdentityStatus;
import com.bankingplatform.user.repository.CustomerIdentityRepository;
import com.bankingplatform.user.dto.*;
import com.bankingplatform.user.exception.DuplicateResourceException;
import com.bankingplatform.user.exception.ResourceNotFoundException;
import com.bankingplatform.user.mapper.UserMapper;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.UserRepository;
import com.bankingplatform.user.service.TwoFactorService;
import com.bankingplatform.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final CustomerIdentityRepository customerIdentityRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final TwoFactorService twoFactorService;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username already taken: " + request.getUsername());
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered: " + request.getEmail());
        }

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        // Store canonical values, not whatever shape the customer typed them in.
        user.setPhone(digitsOnly(request.getPhone()));
        user.setState(request.getState().toUpperCase(Locale.US));
        user.setPostalCode(request.getPostalCode().trim());

        User saved = userRepository.save(user);

        /*
         * The Social Security number ends here. It arrived on the request, it is
         * reduced to its last four digits, and the local variable holding the
         * rest goes out of scope with this method. Nothing writes it to a
         * column, a log line or a response.
         */
        customerIdentityRepository.save(CustomerIdentity.builder()
                .userId(saved.getId())
                .ssnLast4(lastFourDigits(request.getSsn()))
                .status(IdentityStatus.SUBMITTED)
                .build());

        UserDetails userDetails = buildUserDetails(saved);
        String token = jwtUtil.generateToken(userDetails, saved.getId());

        return AuthResponse.builder()
                .token(token)
                .userId(saved.getId())
                .username(saved.getUsername())
                .role(saved.getRole().name())
                .expiresIn(jwtUtil.getExpiration())
                .build();
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.getUsername()));

        // Password accepted. If the account carries a second factor, no session is
        // issued until a valid TOTP code is presented.
        if (user.isTwoFactorEnabled()) {
            String code = request.getTotpCode();
            if (code == null || code.isBlank()) {
                return AuthResponse.builder()
                        .twoFactorRequired(true)
                        .userId(user.getId())
                        .username(user.getUsername())
                        .build();
            }
            if (!twoFactorService.verifyCode(user.getId(), code)) {
                throw new BadCredentialsException("Invalid authentication code");
            }
        }

        UserDetails userDetails = buildUserDetails(user);
        String token = jwtUtil.generateToken(userDetails, user.getId());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole().name())
                .expiresIn(jwtUtil.getExpiration())
                .build();
    }

    @Override
    public UserResponse getUserById(Long id) {
        return userRepository.findById(id)
                .map(this::toResponseWithIdentity)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    @Override
    public UserResponse getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::toResponseWithIdentity)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    /**
     * Adds the identity summary a single customer's profile needs.
     *
     * <p>Only the last four digits and the status: the full number is not
     * stored, so there is nothing else this could return. Accounts created
     * before onboarding existed have no identity record, and simply carry no
     * identity fields rather than failing the read.
     */
    private UserResponse toResponseWithIdentity(User user) {
        UserResponse response = userMapper.toResponse(user);
        customerIdentityRepository.findByUserId(user.getId()).ifPresent(identity -> {
            response.setSsnLast4(identity.getSsnLast4());
            response.setIdentityStatus(identity.getStatus().name());
        });
        return response;
    }

    @Override
    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null) user.setLastName(request.getLastName());
        if (request.getPhone() != null) user.setPhone(request.getPhone());

        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(userMapper::toResponse)
                .toList();
    }

    private UserDetails buildUserDetails(User user) {
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }

    /** Digits only, which is the shape the phone column holds. */
    private static String digitsOnly(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }

    /**
     * The only part of a Social Security number this system keeps.
     *
     * <p>Takes the last four digits after any formatting is stripped. The
     * argument is not logged, not returned and not stored anywhere else.
     */
    private static String lastFourDigits(String ssn) {
        String digits = digitsOnly(ssn);
        return digits.substring(digits.length() - 4);
    }
}
