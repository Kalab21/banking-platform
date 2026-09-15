package com.bankingplatform.user.controller;

import com.bankingplatform.user.dto.CreditScoreHistoryResponse;
import com.bankingplatform.user.dto.CreditScoreResponse;
import com.bankingplatform.user.dto.UpdateCreditScoreRequest;
import com.bankingplatform.user.service.CreditScoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CreditScoreController {

    private final CreditScoreService creditScoreService;

    @GetMapping("/api/users/{userId}/credit-score")
    public ResponseEntity<CreditScoreResponse> getScore(@PathVariable Long userId) {
        return ResponseEntity.ok(creditScoreService.getScore(userId));
    }

    @GetMapping("/api/users/{userId}/credit-score/history")
    public ResponseEntity<List<CreditScoreHistoryResponse>> getHistory(@PathVariable Long userId) {
        return ResponseEntity.ok(creditScoreService.getHistory(userId));
    }

    @PutMapping("/api/users/{userId}/credit-score")
    @PreAuthorize("hasRole('EMPLOYEE') or hasRole('ADMIN')")
    public ResponseEntity<CreditScoreResponse> updateScore(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateCreditScoreRequest request) {
        return ResponseEntity.ok(creditScoreService.updateScore(userId, request.getDelta(), request.getReason()));
    }
}
