package com.bankingplatform.user.service.impl;

import com.bankingplatform.user.dto.TwoFactorSetupResponse;
import com.bankingplatform.user.exception.ResourceNotFoundException;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.UserRepository;
import com.bankingplatform.user.service.TwoFactorService;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
@Slf4j
public class TwoFactorServiceImpl implements TwoFactorService {

    private final UserRepository userRepository;
    private final SecretGenerator secretGenerator;
    private final CodeVerifier codeVerifier;

    public TwoFactorServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.secretGenerator = new DefaultSecretGenerator();
        this.codeVerifier = new DefaultCodeVerifier(
                new DefaultCodeGenerator(),
                new SystemTimeProvider()
        );
    }

    @Override
    @Transactional
    public TwoFactorSetupResponse generateSecret(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (user.isTwoFactorEnabled()) {
            throw new ResponseStatusException(CONFLICT, "2FA already enabled for this user");
        }

        String secret = secretGenerator.generate();
        user.setTwoFactorSecret(secret);
        userRepository.save(user);

        String otpauthUri = "otpauth://totp/BankingPlatform:" + user.getUsername()
                + "?secret=" + secret
                + "&issuer=BankingPlatform"
                + "&algorithm=SHA1&digits=6&period=30";

        log.info("2FA secret generated for userId={}", userId);
        return new TwoFactorSetupResponse(
                secret,
                otpauthUri,
                "Scan the QR code or enter the secret in your authenticator app, then call /verify"
        );
    }

    @Override
    @Transactional
    public void verifyAndEnable(Long userId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (user.getTwoFactorSecret() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Call /setup first to generate a secret");
        }
        if (user.isTwoFactorEnabled()) {
            throw new ResponseStatusException(CONFLICT, "2FA already enabled");
        }
        if (!codeVerifier.isValidCode(user.getTwoFactorSecret(), code)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid TOTP code");
        }

        user.setTwoFactorEnabled(true);
        userRepository.save(user);
        log.info("2FA enabled for userId={}", userId);
    }

    @Override
    @Transactional
    public void disable(Long userId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (!user.isTwoFactorEnabled()) {
            throw new ResponseStatusException(BAD_REQUEST, "2FA is not enabled");
        }
        if (!codeVerifier.isValidCode(user.getTwoFactorSecret(), code)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid TOTP code");
        }

        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
        log.info("2FA disabled for userId={}", userId);
    }

    @Override
    public boolean verifyCode(Long userId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (!user.isTwoFactorEnabled() || user.getTwoFactorSecret() == null) {
            return false;
        }
        return codeVerifier.isValidCode(user.getTwoFactorSecret(), code);
    }
}
