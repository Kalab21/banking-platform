package com.bankingplatform.payment.controller;

import com.bankingplatform.payment.dto.CreatePaymentRequest;
import com.bankingplatform.payment.dto.PaymentResponse;
import com.bankingplatform.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @Operation(summary = "Create and optionally schedule a payment")
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.createPayment(request));
    }

    @GetMapping("/ref/{ref}")
    @Operation(summary = "Get payment by reference number")
    public ResponseEntity<PaymentResponse> getByRef(@PathVariable String ref) {
        return ResponseEntity.ok(paymentService.getByRef(ref));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment by ID")
    public ResponseEntity<PaymentResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getById(id));
    }

    @GetMapping("/account/{accountId}")
    @Operation(summary = "Get all payments for an account")
    public ResponseEntity<List<PaymentResponse>> getByAccount(@PathVariable Long accountId) {
        return ResponseEntity.ok(paymentService.getByPayerAccount(accountId));
    }

    @GetMapping("/account/{accountId}/scheduled")
    @Operation(summary = "Get pending scheduled payments for an account")
    public ResponseEntity<List<PaymentResponse>> getScheduled(@PathVariable Long accountId) {
        return ResponseEntity.ok(paymentService.getScheduledByPayerAccount(accountId));
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending payment")
    public ResponseEntity<PaymentResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.cancel(id));
    }
}
