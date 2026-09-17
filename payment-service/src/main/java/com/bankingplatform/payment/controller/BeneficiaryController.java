package com.bankingplatform.payment.controller;

import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.payment.dto.BeneficiaryResponse;
import com.bankingplatform.payment.dto.CreateBeneficiaryRequest;
import com.bankingplatform.payment.service.BeneficiaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Payees a customer has saved, reached through the API gateway.
 *
 * <p>A beneficiary record names someone the customer pays: their name and the
 * account it goes to. It is ordinary customer data and was readable by anyone
 * who could guess a user id, because the {@code userId} in the path was taken
 * as proof of entitlement rather than as the identifier of a resource.
 *
 * <p>Reads and deletes now resolve the stored owner and authorise against that.
 * Creation authorises the target user before anything is written.
 */
@RestController
@RequestMapping("/api/payments/beneficiaries")
@RequiredArgsConstructor
@Tag(name = "Beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    @PostMapping
    @Operation(summary = "Add a new beneficiary")
    public ResponseEntity<BeneficiaryResponse> create(@Valid @RequestBody CreateBeneficiaryRequest request,
                                                       CallerIdentity caller) {
        // A customer may add a payee for themselves; staff may act for anyone.
        AccessGuard.requireTargetUserAllowed(caller, request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(beneficiaryService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get beneficiary by ID")
    public ResponseEntity<BeneficiaryResponse> getById(@PathVariable Long id, CallerIdentity caller) {
        BeneficiaryResponse beneficiary = beneficiaryService.getById(id);
        AccessGuard.requireOwnerOrStaff(caller, beneficiary.getUserId());
        return ResponseEntity.ok(beneficiary);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all beneficiaries for a user")
    public ResponseEntity<List<BeneficiaryResponse>> getByUser(@PathVariable Long userId,
                                                                CallerIdentity caller) {
        AccessGuard.requireTargetUserAllowed(caller, userId);
        return ResponseEntity.ok(beneficiaryService.getByUserId(userId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a beneficiary")
    public ResponseEntity<Void> delete(@PathVariable Long id, CallerIdentity caller) {
        // The owner comes from the stored record. This used to take a userId
        // query parameter and hand it to the service as the authority for the
        // delete, so naming someone else's id deleted their payee.
        BeneficiaryResponse beneficiary = beneficiaryService.getById(id);
        AccessGuard.requireOwnerOrStaff(caller, beneficiary.getUserId());

        beneficiaryService.delete(id, beneficiary.getUserId());
        return ResponseEntity.noContent().build();
    }
}
