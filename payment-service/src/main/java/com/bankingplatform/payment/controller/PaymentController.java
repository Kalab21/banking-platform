package com.bankingplatform.payment.controller;

import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.payment.dto.CreatePaymentRequest;
import com.bankingplatform.payment.dto.PaymentResponse;
import com.bankingplatform.payment.security.PaymentOwnershipVerifier;
import com.bankingplatform.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Payments, reached through the API gateway.
 *
 * <p>A payment belongs to the account it is paid from, so every operation here
 * authorises against that account's owner. An account id in a path names a
 * resource; it is not evidence that the caller may reach it.
 *
 * <p>Creation is authorised before the payment is written, because a payment
 * moves money: refusing afterwards would leave the transfer already made.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentOwnershipVerifier ownership;

    @PostMapping
    @Operation(summary = "Create and optionally schedule a payment")
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request,
                                                   CallerIdentity caller) {
        // Paying *to* another customer's account is ordinary banking; paying
        // *from* one is not, so only the payer account is owner-checked.
        ownership.requireCanAccessAccount(caller, request.getPayerAccountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.createPayment(request));
    }

    @GetMapping("/ref/{ref}")
    @Operation(summary = "Get payment by reference number")
    public ResponseEntity<PaymentResponse> getByRef(@PathVariable String ref, CallerIdentity caller) {
        // A reference is a guessable handle, so the account behind it decides
        // who may read it.
        PaymentResponse payment = paymentService.getByRef(ref);
        ownership.requireCanAccessAccount(caller, payment.getPayerAccountId());
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment by ID")
    public ResponseEntity<PaymentResponse> getById(@PathVariable Long id, CallerIdentity caller) {
        PaymentResponse payment = paymentService.getById(id);
        ownership.requireCanAccessAccount(caller, payment.getPayerAccountId());
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/account/{accountId}")
    @Operation(summary = "Get all payments for an account")
    public ResponseEntity<List<PaymentResponse>> getByAccount(@PathVariable Long accountId,
                                                               CallerIdentity caller) {
        ownership.requireCanAccessAccount(caller, accountId);
        return ResponseEntity.ok(paymentService.getByPayerAccount(accountId));
    }

    @GetMapping("/account/{accountId}/scheduled")
    @Operation(summary = "Get pending scheduled payments for an account")
    public ResponseEntity<List<PaymentResponse>> getScheduled(@PathVariable Long accountId,
                                                               CallerIdentity caller) {
        ownership.requireCanAccessAccount(caller, accountId);
        return ResponseEntity.ok(paymentService.getScheduledByPayerAccount(accountId));
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending payment")
    public ResponseEntity<PaymentResponse> cancel(@PathVariable Long id, CallerIdentity caller) {
        PaymentResponse payment = paymentService.getById(id);
        ownership.requireCanAccessAccount(caller, payment.getPayerAccountId());
        return ResponseEntity.ok(paymentService.cancel(id));
    }
}
