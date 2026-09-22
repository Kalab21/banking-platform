package com.bankingplatform.account.controller;

import com.bankingplatform.account.dto.AccountResponse;
import com.bankingplatform.account.dto.BalanceUpdateRequest;
import com.bankingplatform.account.dto.MovementStatusResponse;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.service.AccountService;
import com.bankingplatform.common.idempotency.IdempotencyGuard;
import com.bankingplatform.common.idempotency.IdempotencyStatus;
import com.bankingplatform.common.idempotency.IdempotencyStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
    private final IdempotencyGuard idempotency;
    private final IdempotencyStore idempotencyStore;

    private static final String BALANCE = "BALANCE";

    private static final String KEY_DESCRIPTION =
            "Names one balance movement. The calling service derives it from the "
                    + "transaction or payment reference the movement belongs to, so a retry "
                    + "of that movement carries the same key and a new movement does not.";

    /**
     * Credit or debit a balance, at most once per key.
     *
     * <p>This is the one place on the platform where a balance actually
     * changes. Every service that moves money arrives here over HTTP, and a
     * call that times out tells the caller nothing about whether it applied.
     * Without a key the caller has only bad options: never retry, and strand
     * a movement that may not have happened; or retry, and debit twice.
     *
     * <p>The key is <b>required</b>. An endpoint that is idempotent only when
     * asked is the worst of both — it looks safe and is not, and the caller
     * that most needs the guarantee is the one that forgot to ask for it.
     *
     * <p>No caller identity, deliberately, and none in the fingerprint. This
     * endpoint is reachable only from inside the Compose network and takes no
     * {@code CallerIdentity} — an endpoint that accepted one would be
     * admitting it is callable by a user. The key itself is scoped by the
     * calling service, which derives it from its own transaction reference.
     */
    @PutMapping("/{id}/balance")
    @Operation(summary = "Credit or debit an account balance (service-to-service only)")
    public ResponseEntity<AccountResponse> updateBalance(
            @PathVariable Long id,
            @Valid @RequestBody BalanceUpdateRequest request,
            @Parameter(description = KEY_DESCRIPTION)
            @RequestHeader(name = IdempotencyGuard.HEADER, required = false) String idempotencyKey) {

        // The account is a path variable, so a fingerprint over the body
        // alone would make the same amount against two different accounts
        // look like one request -- and the second account would be served the
        // first one's response and never move.
        return idempotency.execute(idempotencyKey, BALANCE, null,
                Map.of("accountId", id, "request", request),
                AccountResponse.class, response -> String.valueOf(response.getId()),
                // 200, not 201: a balance movement creates nothing, and a
                // replay answering 201 would be inventing a resource.
                HttpStatus.OK,
                () -> accountService.updateBalance(id, request));
    }

    /**
     * Whether a balance movement was applied, by the key it was sent under.
     *
     * <p>The answer a caller cannot work out for itself. A transfer whose
     * debit timed out does not know whether the money left; this service
     * does, because the idempotency record is written by the same transaction
     * that moved the balance.
     *
     * <p>{@code NOT_FOUND} is a real answer and not an error: it means the
     * request never arrived here, so nothing was applied and the caller is
     * free to send it.
     *
     * <p>Read-only, and deliberately so. It reports what happened; deciding
     * what to do about a half-applied transfer is not this endpoint's
     * business, and is not something the platform does automatically.
     */
    @GetMapping("/movements/{idempotencyKey}")
    @Operation(summary = "Whether a balance movement was applied (service-to-service only)")
    public ResponseEntity<MovementStatusResponse> movementStatus(@PathVariable String idempotencyKey) {
        return ResponseEntity.ok(idempotencyStore.find(idempotencyKey)
                .map(record -> MovementStatusResponse.builder()
                        .idempotencyKey(idempotencyKey)
                        .applied(record.status() == IdempotencyStatus.COMPLETED)
                        .status(record.status().name())
                        .build())
                .orElseGet(() -> MovementStatusResponse.builder()
                        .idempotencyKey(idempotencyKey)
                        .applied(false)
                        .status("NOT_FOUND")
                        .build()));
    }

    /**
     * Who owns an account, so a service about to move money can check that the
     * account belongs to the customer whose product it is settling.
     *
     * <p>Without this, {@code loan-service} and {@code credit-card-service} had
     * no way to ask. They debited whatever account id the caller sent, so a
     * customer could repay their own loan, or pay down their own card, out of
     * somebody else's balance — they owned the product, and nothing checked
     * that they owned the money.
     *
     * <p>Returns the whole account rather than just an owner id: the caller
     * usually wants the currency and the status too, and a second round trip
     * to fetch them would be worse than one honest read.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Read an account (service-to-service only)")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountById(id));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Set account status (service-to-service only, e.g. fraud freeze)")
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable Long id,
                                                         @RequestParam AccountStatus status) {
        return ResponseEntity.ok(accountService.updateStatus(id, status));
    }
}
