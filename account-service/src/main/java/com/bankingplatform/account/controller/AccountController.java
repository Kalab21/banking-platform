package com.bankingplatform.account.controller;

import com.bankingplatform.account.dto.*;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Create a new bank account")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get account by ID")
    public ResponseEntity<AccountResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountById(id));
    }

    @GetMapping("/number/{accountNumber}")
    @Operation(summary = "Get account by account number")
    public ResponseEntity<AccountResponse> getByNumber(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.getAccountByNumber(accountNumber));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all accounts for a user")
    public ResponseEntity<List<AccountResponse>> getByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(accountService.getAccountsByUserId(userId));
    }

    @GetMapping("/user/{userId}/active")
    @Operation(summary = "Get active accounts for a user")
    public ResponseEntity<List<AccountResponse>> getActiveByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(accountService.getActiveAccountsByUserId(userId));
    }

    @PutMapping("/{id}/balance")
    @Operation(summary = "Credit or debit account balance")
    public ResponseEntity<AccountResponse> updateBalance(@PathVariable Long id,
                                                          @Valid @RequestBody BalanceUpdateRequest request) {
        return ResponseEntity.ok(accountService.updateBalance(id, request));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Update account status (ACTIVE/FROZEN/CLOSED)")
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable Long id,
                                                         @RequestParam AccountStatus status) {
        return ResponseEntity.ok(accountService.updateStatus(id, status));
    }

    @PutMapping("/{id}/overdraft-limit")
    @Operation(summary = "Update account overdraft limit")
    public ResponseEntity<AccountResponse> updateOverdraftLimit(@PathVariable Long id,
                                                                  @Valid @RequestBody UpdateOverdraftRequest request) {
        return ResponseEntity.ok(accountService.updateOverdraftLimit(id, request));
    }
}
