package com.bankingplatform.user.controller;

import com.bankingplatform.user.dto.TwoFactorSetupResponse;
import com.bankingplatform.user.dto.TwoFactorVerifyRequest;
import com.bankingplatform.user.service.TwoFactorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/2fa")
@RequiredArgsConstructor
public class TwoFactorController {

    private final TwoFactorService twoFactorService;

    @PostMapping("/setup")
    public ResponseEntity<TwoFactorSetupResponse> setup(@RequestParam Long userId) {
        return ResponseEntity.ok(twoFactorService.generateSecret(userId));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(
            @RequestParam Long userId,
            @Valid @RequestBody TwoFactorVerifyRequest request) {
        twoFactorService.verifyAndEnable(userId, request.getCode());
        return ResponseEntity.ok(Map.of("message", "2FA enabled successfully"));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> disable(
            @RequestParam Long userId,
            @Valid @RequestBody TwoFactorVerifyRequest request) {
        twoFactorService.disable(userId, request.getCode());
        return ResponseEntity.ok(Map.of("message", "2FA disabled successfully"));
    }
}
