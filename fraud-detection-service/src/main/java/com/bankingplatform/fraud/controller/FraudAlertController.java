package com.bankingplatform.fraud.controller;

import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
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

/**
 * Fraud alerts and their review, reached through the API gateway.
 *
 * <p>Every endpoint here is staff-only, which is the contract the product
 * documents and the only one the console uses — the fraud views live under the
 * staff area. A fraud alert is a control applied <em>to</em> a customer, so
 * exposing it to that customer would be the wrong direction: the alert names
 * the rule that fired and the amount that tripped it, and the review operation
 * is how the bank closes it.
 *
 * <p>Before this, none of these methods took a caller at all. Any authenticated
 * customer could list every open alert on the platform, read the alerts on any
 * account, and — worst of the three — resolve an alert while naming themselves
 * as the reviewer. That last one let the subject of a fraud control switch it
 * off.
 */
@RestController
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
@Tag(name = "Fraud Detection")
public class FraudAlertController {

    private final FraudDetectionService fraudService;

    @GetMapping("/alerts")
    @Operation(summary = "List all open fraud alerts (staff only)")
    public ResponseEntity<List<FraudAlertResponse>> getOpenAlerts(CallerIdentity caller) {
        // Platform-wide: every open alert across every customer.
        AccessGuard.requireStaff(caller);
        return ResponseEntity.ok(
                fraudService.getOpenAlerts().stream().map(this::toResponse).toList());
    }

    @GetMapping("/alerts/account/{accountId}")
    @Operation(summary = "Get fraud alerts for an account (staff only)")
    public ResponseEntity<Page<FraudAlertResponse>> getAlertsByAccount(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            CallerIdentity caller) {
        AccessGuard.requireStaff(caller);
        return ResponseEntity.ok(
                fraudService.getAlertsByAccount(accountId, page, size).map(this::toResponse));
    }

    @PutMapping("/alerts/{id}/review")
    @Operation(summary = "Review or resolve a fraud alert (staff only)")
    public ResponseEntity<FraudAlertResponse> reviewAlert(
            @PathVariable Long id,
            @RequestBody ReviewAlertRequest req,
            CallerIdentity caller) {
        AccessGuard.requireStaff(caller);

        // The reviewer is whoever the gateway says is calling, never a value
        // from the body. The request used to carry its own reviewedBy, so the
        // audit trail recorded whichever identity the caller typed.
        AlertStatus status = req.getStatus() != null ? req.getStatus() : AlertStatus.REVIEWED;
        FraudAlert updated = fraudService.reviewAlert(id, status, caller.userId(), req.getResolutionNote());
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
