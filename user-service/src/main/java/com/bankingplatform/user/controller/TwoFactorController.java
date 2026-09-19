package com.bankingplatform.user.controller;

import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.user.dto.TwoFactorSetupResponse;
import com.bankingplatform.user.dto.TwoFactorVerifyRequest;
import com.bankingplatform.user.service.TwoFactorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Enrolling, confirming and removing a second factor.
 *
 * <p>These three endpoints manage a credential, so the rule is stricter than it
 * is elsewhere in this service: {@link AccessGuard#requireSelf} rather than
 * {@code requireOwnerOrStaff}. An employee may review a customer's KYC
 * documents because a workflow needs it; nobody, at any role, needs to enrol or
 * remove someone else's authenticator. A staff account that could disable a
 * customer's second factor is a staff account that could take it off before
 * signing in as them.
 *
 * <p>The {@code userId} arrives as a request parameter, which is caller input.
 * It is checked against the identity the gateway derived from the token, and a
 * mismatch is refused before the service is reached — so a denied call cannot
 * rotate a secret, and it cannot reveal whether the named account has a second
 * factor at all.
 */
@RestController
@RequestMapping("/api/auth/2fa")
@RequiredArgsConstructor
public class TwoFactorController {

    private final TwoFactorService twoFactorService;

    @PostMapping("/setup")
    public ResponseEntity<TwoFactorSetupResponse> setup(@RequestParam Long userId, CallerIdentity caller) {
        AccessGuard.requireSelf(caller, userId);
        return ResponseEntity.ok(twoFactorService.generateSecret(userId));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(
            @RequestParam Long userId,
            @Valid @RequestBody TwoFactorVerifyRequest request,
            CallerIdentity caller) {
        AccessGuard.requireSelf(caller, userId);
        twoFactorService.verifyAndEnable(userId, request.getCode());
        return ResponseEntity.ok(Map.of("message", "2FA enabled successfully"));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> disable(
            @RequestParam Long userId,
            @Valid @RequestBody TwoFactorVerifyRequest request,
            CallerIdentity caller) {
        AccessGuard.requireSelf(caller, userId);
        twoFactorService.disable(userId, request.getCode());
        return ResponseEntity.ok(Map.of("message", "2FA disabled successfully"));
    }
}
