package com.bankingplatform.account.controller;

import com.bankingplatform.account.dto.*;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.service.AccountService;
import com.bankingplatform.common.security.AccessDeniedException;
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
 * <p>Balance mutation is deliberately absent. It is a service-to-service
 * operation and lives on {@link InternalAccountController}, which the gateway
 * does not route, so it cannot be invoked by a customer.
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
        // An overdraft limit is credit the bank extends, and changing one is
        // already staff-only. Stating it while opening the account was the
        // same decision through a side door: a customer could open a checking
        // account with a limit of their choosing and withdraw against it.
        // Staff opening their own account are customers for that account.
        if (request.getOverdraftLimit() != null && (!caller.isStaff() || caller.isSelf(request.getUserId()))) {
            throw new AccessDeniedException("The overdraft limit is set by the bank");
        }
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

    @PutMapping("/{id}/status")
    @Operation(summary = "Update account status (ACTIVE/FROZEN/CLOSED)")
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable Long id,
                                                         @RequestParam AccountStatus status,
                                                         CallerIdentity caller) {
        // Freezing or closing an account is a staff action. A customer must not
        // be able to unfreeze an account that fraud detection froze -- nor may
        // a member of staff lift a freeze on their own.
        AccessGuard.requireStaff(caller);
        AccessGuard.requireStaffActingForAnother(caller, accountService.getAccountById(id).getUserId());
        return ResponseEntity.ok(accountService.updateStatus(id, status));
    }

    @PutMapping("/{id}/overdraft-limit")
    @Operation(summary = "Update account overdraft limit")
    public ResponseEntity<AccountResponse> updateOverdraftLimit(@PathVariable Long id,
                                                                  @Valid @RequestBody UpdateOverdraftRequest request,
                                                                  CallerIdentity caller) {
        // An overdraft limit is a lending decision, not a customer preference,
        // and not one staff take about their own account.
        AccessGuard.requireStaff(caller);
        AccessGuard.requireStaffActingForAnother(caller, accountService.getAccountById(id).getUserId());
        return ResponseEntity.ok(accountService.updateOverdraftLimit(id, request));
    }
}
