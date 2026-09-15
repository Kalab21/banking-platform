package com.bankingplatform.payment.controller;

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

@RestController
@RequestMapping("/api/payments/beneficiaries")
@RequiredArgsConstructor
@Tag(name = "Beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    @PostMapping
    @Operation(summary = "Add a new beneficiary")
    public ResponseEntity<BeneficiaryResponse> create(@Valid @RequestBody CreateBeneficiaryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(beneficiaryService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get beneficiary by ID")
    public ResponseEntity<BeneficiaryResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(beneficiaryService.getById(id));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all beneficiaries for a user")
    public ResponseEntity<List<BeneficiaryResponse>> getByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(beneficiaryService.getByUserId(userId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a beneficiary")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam Long userId) {
        beneficiaryService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }
}
