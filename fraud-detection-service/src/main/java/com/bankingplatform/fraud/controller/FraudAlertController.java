package com.bankingplatform.fraud.controller;

import com.bankingplatform.fraud.dto.FraudAlertResponse;
import com.bankingplatform.fraud.dto.ReviewAlertRequest;
import com.bankingplatform.fraud.model.AlertStatus;
import com.bankingplatform.fraud.model.FraudAlert;
import com.bankingplatform.fraud.service.FraudDetectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
@Tag(name = "Fraud Detection")
public class FraudAlertController {

    private final FraudDetectionService fraudService;

    @GetMapping("/alerts")
    @Operation(summary = "List all open fraud alerts (admin)")
    public ResponseEntity<List<FraudAlertResponse>> getOpenAlerts() {
        return ResponseEntity.ok(
                fraudService.getOpenAlerts().stream().map(this::toResponse).toList());
    }

    @GetMapping("/alerts/account/{accountId}")
    @Operation(summary = "Get fraud alerts for an account")
    public ResponseEntity<Page<FraudAlertResponse>> getAlertsByAccount(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                fraudService.getAlertsByAccount(accountId, page, size).map(this::toResponse));
    }

    @PutMapping("/alerts/{id}/review")
    @Operation(summary = "Review / resolve a fraud alert")
    public ResponseEntity<FraudAlertResponse> reviewAlert(
            @PathVariable Long id,
            @RequestBody ReviewAlertRequest req) {
        AlertStatus status = req.getStatus() != null ? req.getStatus() : AlertStatus.REVIEWED;
        FraudAlert updated = fraudService.reviewAlert(id, status, req.getReviewedBy(), req.getResolutionNote());
        return ResponseEntity.ok(toResponse(updated));
    }

    private FraudAlertResponse toResponse(FraudAlert a) {
        return FraudAlertResponse.builder()
                .id(a.getId())
                .accountId(a.getAccountId())
                .userId(a.getUserId())
                .alertType(a.getAlertType())
                .riskScore(a.getRiskScore())
                .description(a.getDescription())
                .eventRef(a.getEventRef())
                .eventType(a.getEventType())
                .amount(a.getAmount())
                .status(a.getStatus())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
