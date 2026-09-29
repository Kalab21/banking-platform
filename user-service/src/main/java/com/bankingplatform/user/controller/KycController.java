package com.bankingplatform.user.controller;

import com.bankingplatform.user.dto.KycDocumentRequest;
import com.bankingplatform.user.dto.KycDocumentResponse;
import com.bankingplatform.user.dto.ReviewDocumentRequest;
import com.bankingplatform.user.dto.UserResponse;
import com.bankingplatform.user.model.KycStatus;
import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.user.service.KycService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class KycController {

    private final KycService kycService;

    @PostMapping("/api/users/{userId}/kyc/documents")
    public ResponseEntity<KycDocumentResponse> submitDocument(
            @PathVariable Long userId,
            @Valid @RequestBody KycDocumentRequest request,
            CallerIdentity caller) {
        // The customer submits their own evidence. Owner-or-staff let a member
        // of staff upload a document for a customer and then approve it, one
        // person doing both halves of the check.
        AccessGuard.requireSelf(caller, userId);
        return ResponseEntity.status(201).body(kycService.submitDocument(userId, request));
    }

    @GetMapping("/api/users/{userId}/kyc/documents")
    public ResponseEntity<List<KycDocumentResponse>> getUserDocuments(@PathVariable Long userId,
                                                                      CallerIdentity caller) {
        // KYC documents are identity evidence; another customer must never be
        // able to list them.
        AccessGuard.requireOwnerOrStaff(caller, userId);
        return ResponseEntity.ok(kycService.getUserDocuments(userId));
    }

    @GetMapping("/api/users/{userId}/kyc")
    public ResponseEntity<List<KycDocumentResponse>> getKycStatus(@PathVariable Long userId,
                                                                  CallerIdentity caller) {
        AccessGuard.requireOwnerOrStaff(caller, userId);
        return ResponseEntity.ok(kycService.getUserDocuments(userId));
    }

    @PutMapping("/api/kyc/documents/{documentId}/review")
    @PreAuthorize("hasRole('EMPLOYEE') or hasRole('ADMIN')")
    public ResponseEntity<KycDocumentResponse> reviewDocument(
            @PathVariable Long documentId,
            @Valid @RequestBody ReviewDocumentRequest request,
            CallerIdentity caller) {
        // Who reviewed it is the caller, not a field in the body: the console
        // used to send its own reviewer id and could have sent anyone's.
        request.setReviewedBy(caller.userId());
        if (request.getStatus() == com.bankingplatform.user.model.DocumentStatus.REJECTED
                && (request.getRejectionReason() == null || request.getRejectionReason().isBlank())) {
            throw new IllegalArgumentException("A rejected document needs a reason the customer can act on");
        }
        return ResponseEntity.ok(kycService.reviewDocument(documentId, request, caller.userId()));
    }

    @PutMapping("/api/users/{userId}/kyc/status")
    @PreAuthorize("hasRole('EMPLOYEE') or hasRole('ADMIN')")
    public ResponseEntity<UserResponse> updateKycStatus(
            @PathVariable Long userId,
            @RequestParam KycStatus status,
            CallerIdentity caller) {
        // A member of staff does not verify their own identity.
        if (caller.isSelf(userId)) {
            throw new com.bankingplatform.common.security.AccessDeniedException(
                    "Staff cannot decide their own identity check");
        }
        return ResponseEntity.ok(kycService.updateKycStatus(userId, status));
    }
}
