package com.bankingplatform.transaction.controller;

import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.transaction.dto.*;
import com.bankingplatform.transaction.idempotency.IdempotencyGuard;
import com.bankingplatform.transaction.security.AccountOwnershipVerifier;
import com.bankingplatform.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Money movement, reached through the API gateway.
 *
 * <p>An account id in a request body is caller input. Possession of one is not
 * authority to move money from it, so every operation resolves the account's
 * owner and authorises the caller against it before the service layer is
 * reached. Authorising first is what keeps a refused request from leaving a
 * debit already applied.
 *
 * <p>Each operation also requires an {@code Idempotency-Key}. The ordering
 * here is deliberate and is part of the security model: ownership is checked
 * <em>before</em> the key is claimed, so a denied request stores nothing and a
 * key can never carry a result across principals.
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions")
public class TransactionController {

    private static final String DEPOSIT = "DEPOSIT";
    private static final String WITHDRAWAL = "WITHDRAWAL";
    private static final String TRANSFER = "TRANSFER";

    private static final String KEY_DESCRIPTION =
            "Opaque client-generated value naming this logical operation. Reuse it to retry the "
                    + "same operation safely; use a new one for a new operation.";

    private final TransactionService transactionService;
    private final AccountOwnershipVerifier ownership;
    private final IdempotencyGuard idempotency;

    @PostMapping("/deposit")
    @Operation(summary = "Deposit money into an account")
    public ResponseEntity<TransactionResponse> deposit(
            @Valid @RequestBody DepositRequest request,
            @Parameter(description = KEY_DESCRIPTION)
            @RequestHeader(name = IdempotencyGuard.HEADER, required = false) String idempotencyKey,
            CallerIdentity caller) {
        // Restricted to the account holder rather than left open. A real bank
        // accepts third-party deposits, but nothing in this product needs one,
        // and an unrestricted credit endpoint is an obvious way to place funds
        // into an account the depositor does not control.
        ownership.requireCanAccess(caller, request.getAccountId());
        return idempotency.execute(idempotencyKey, DEPOSIT, caller, request,
                TransactionResponse.class, TransactionResponse::getTransactionRef,
                () -> transactionService.deposit(request));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw money from an account")
    public ResponseEntity<TransactionResponse> withdraw(
            @Valid @RequestBody WithdrawRequest request,
            @Parameter(description = KEY_DESCRIPTION)
            @RequestHeader(name = IdempotencyGuard.HEADER, required = false) String idempotencyKey,
            CallerIdentity caller) {
        ownership.requireCanAccess(caller, request.getAccountId());
        return idempotency.execute(idempotencyKey, WITHDRAWAL, caller, request,
                TransactionResponse.class, TransactionResponse::getTransactionRef,
                () -> transactionService.withdraw(request));
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer money between two accounts")
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @Parameter(description = KEY_DESCRIPTION)
            @RequestHeader(name = IdempotencyGuard.HEADER, required = false) String idempotencyKey,
            CallerIdentity caller) {
        // Only the source is owner-checked. Transferring *to* another
        // customer's account is ordinary banking; transferring *from* one is
        // theft, and was previously possible by supplying any fromAccountId.
        ownership.requireCanAccess(caller, request.getFromAccountId());
        return idempotency.execute(idempotencyKey, TRANSFER, caller, request,
                TransferResponse.class, result -> result.getDebit().getTransactionRef(),
                () -> transactionService.transfer(request));
    }

    @GetMapping("/{ref}")
    @Operation(summary = "Get transaction by reference number")
    public ResponseEntity<TransactionResponse> getByRef(@PathVariable String ref, CallerIdentity caller) {
        TransactionResponse transaction = transactionService.getByRef(ref);
        // A reference is a guessable handle to someone's transaction, so the
        // account behind it decides who may read it.
        ownership.requireCanAccess(caller, transaction.getAccountId());
        return ResponseEntity.ok(transaction);
    }

    @GetMapping("/account/{accountId}")
    @Operation(summary = "Get paginated transaction history for an account")
    public ResponseEntity<Page<TransactionResponse>> getByAccount(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            CallerIdentity caller) {
        ownership.requireCanAccess(caller, accountId);
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(transactionService.getByAccountId(accountId, pageable));
    }
}
