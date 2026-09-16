package com.bankingplatform.account.controller;

import com.bankingplatform.account.dto.*;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.service.AccountService;
import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User-facing account operations, reached through the API gateway.
 *
 * <p>Every method takes a {@link CallerIdentity}, which the gateway establishes
 * from the JWT. A valid token is not by itself permission to read or change an
 * account: each method authorises the caller against the account's owner. An
 * id in the path or a {@code userId} in the body is caller input and is never
 * treated as proof of ownership.
 *
 * <p>Balance mutation is the exception: it takes no caller because it is invoked
 * service-to-service by transaction, loan and credit-card workflows, which do
 * not pass through the gateway and so carry no identity. It is relocated behind
 * an internal-only path in a follow-up change; until then it remains reachable
 * through the gateway and is the known gap tracked separately.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Open a new bank account")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request,
                                                          CallerIdentity caller) {
        // A customer may open an account for themselves only. Staff may open
        // one on behalf of any customer.
        AccessGuard.requireTargetUserAllowed(caller, request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get account by ID")
    public ResponseEntity<AccountResponse> getById(@PathVariable Long id, CallerIdentity caller) {
        AccountResponse account = accountService.getAccountById(id);
        AccessGuard.requireOwnerOrStaff(caller, account.getUserId());
        return ResponseEntity.ok(account);
    }

    @GetMapping("/number/{accountNumber}")
    @Operation(summary = "Get account by account number")
    public ResponseEntity<AccountResponse> getByNumber(@PathVariable String accountNumber, CallerIdentity caller) {
        AccountResponse account = accountService.getAccountByNumber(accountNumber);
        AccessGuard.requireOwnerOrStaff(caller, account.getUserId());
        return ResponseEntity.ok(account);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all accounts for a user")
    public ResponseEntity<List<AccountResponse>> getByUser(@PathVariable Long userId, CallerIdentity caller) {
        AccessGuard.requireOwnerOrStaff(caller, userId);
        return ResponseEntity.ok(accountService.getAccountsByUserId(userId));
    }

    @GetMapping("/user/{userId}/active")
    @Operation(summary = "Get active accounts for a user")
    public ResponseEntity<List<AccountResponse>> getActiveByUser(@PathVariable Long userId, CallerIdentity caller) {
        AccessGuard.requireOwnerOrStaff(caller, userId);
        return ResponseEntity.ok(accountService.getActiveAccountsByUserId(userId));
    }

    @PutMapping("/{id}/balance")
    @Operation(summary = "Credit or debit account balance (service-to-service)")
    public ResponseEntity<AccountResponse> updateBalance(@PathVariable Long id,
                                                          @Valid @RequestBody BalanceUpdateRequest request) {
        return ResponseEntity.ok(accountService.updateBalance(id, request));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Update account status (ACTIVE/FROZEN/CLOSED)")
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable Long id,
                                                         @RequestParam AccountStatus status,
                                                         CallerIdentity caller) {
        // Freezing or closing an account is a staff action. A customer must not
        // be able to unfreeze an account that fraud detection froze.
        AccessGuard.requireStaff(caller);
        return ResponseEntity.ok(accountService.updateStatus(id, status));
    }

    @PutMapping("/{id}/overdraft-limit")
    @Operation(summary = "Update account overdraft limit")
    public ResponseEntity<AccountResponse> updateOverdraftLimit(@PathVariable Long id,
                                                                  @Valid @RequestBody UpdateOverdraftRequest request,
                                                                  CallerIdentity caller) {
        // An overdraft limit is a lending decision, not a customer preference.
        AccessGuard.requireStaff(caller);
        return ResponseEntity.ok(accountService.updateOverdraftLimit(id, request));
    }
}
