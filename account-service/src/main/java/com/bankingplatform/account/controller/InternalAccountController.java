package com.bankingplatform.account.controller;

import com.bankingplatform.account.dto.AccountResponse;
import com.bankingplatform.account.dto.BalanceUpdateRequest;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Service-to-service account operations.
 *
 * <p>These are not customer operations and have no customer-facing equivalent.
 * Crediting and debiting a balance is the mechanism behind a transfer, a loan
 * disbursement or a card payment; it is never something a caller should invoke
 * directly. While it sat on {@code /api/accounts/{id}/balance} it was routed
 * through the gateway, so any authenticated customer could credit their own
 * account by an arbitrary amount or debit somebody else's.
 *
 * <p>Freezing an account is here for a different reason: fraud detection acts
 * on a Kafka event, outside any request, so there is no caller identity to
 * authorise and no user on whose behalf it acts. The same operation remains
 * available to staff on the public controller, where it is role-checked.
 *
 * <p>The boundary is the network, not a role check. Nothing routes
 * {@code /internal/**} at the gateway — the discovery locator that would
 * otherwise expose it as {@code /account-service/internal/**} is disabled — and
 * the business services publish no host ports, so these endpoints are reachable
 * only from inside the Compose network. Deliberately no {@code CallerIdentity}
 * parameter: an endpoint that accepted one would be admitting it is callable by
 * a user.
 */
@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts (internal)")
public class InternalAccountController {

    private final AccountService accountService;

    @PutMapping("/{id}/balance")
    @Operation(summary = "Credit or debit an account balance (service-to-service only)")
    public ResponseEntity<AccountResponse> updateBalance(@PathVariable Long id,
                                                          @Valid @RequestBody BalanceUpdateRequest request) {
        return ResponseEntity.ok(accountService.updateBalance(id, request));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Set account status (service-to-service only, e.g. fraud freeze)")
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable Long id,
                                                         @RequestParam AccountStatus status) {
        return ResponseEntity.ok(accountService.updateStatus(id, status));
    }
}
