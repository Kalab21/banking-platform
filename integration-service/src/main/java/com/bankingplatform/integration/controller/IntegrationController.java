package com.bankingplatform.integration.controller;

import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.integration.dto.*;
import com.bankingplatform.integration.security.AccountOwnershipVerifier;
import com.bankingplatform.integration.service.IntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * External transfer rails and currency tools, reached through the API gateway.
 *
 * <p>Authentication at the gateway proves the caller is <em>someone</em>. It
 * does not prove that the {@code fromAccountId} in the body is theirs. Until
 * this change nothing checked: any signed-in customer could send money out of
 * any account by putting its id in a request, and could read back anyone's
 * wire, ACH or SWIFT transfer — beneficiary name, IBAN, routing number and
 * amount — by quoting its reference.
 *
 * <p>Two kinds of endpoint live here, and only one of them has an owner:
 *
 * <ul>
 *   <li><b>Transfers</b> name a source account, so the account's owner decides
 *       who may act on them. The check is delegated to
 *       {@link AccountOwnershipVerifier}, which resolves the owner from
 *       account-service — the service that actually holds the account — rather
 *       than trusting the request.</li>
 *   <li><b>Exchange rate, conversion and account-number format validation</b>
 *       touch no customer-owned resource. The rate from USD to GBP is the same
 *       for everyone and is derived from a static table. Inventing an ownership
 *       rule for them would be a check that authorises nothing; they stay
 *       behind gateway authentication, which is the control that fits.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService integrationService;
    private final AccountOwnershipVerifier ownership;

    @PostMapping("/wire-transfer")
    public ResponseEntity<TransferResponse> wireTransfer(@RequestBody WireTransferRequest req,
                                                         CallerIdentity caller) {
        // Before the transfer is persisted and its event published: a refused
        // request must leave no record and no event behind it.
        ownership.requireCanAccess(caller, req.getFromAccountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(integrationService.initiateWireTransfer(req));
    }

    @PostMapping("/ach-transfer")
    public ResponseEntity<TransferResponse> achTransfer(@RequestBody AchTransferRequest req,
                                                        CallerIdentity caller) {
        ownership.requireCanAccess(caller, req.getFromAccountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(integrationService.initiateAchTransfer(req));
    }

    @PostMapping("/swift-transfer")
    public ResponseEntity<TransferResponse> swiftTransfer(@RequestBody SwiftTransferRequest req,
                                                          CallerIdentity caller) {
        ownership.requireCanAccess(caller, req.getFromAccountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(integrationService.initiateSwiftTransfer(req));
    }

    @GetMapping("/transfer/{ref}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable String ref, CallerIdentity caller) {
        TransferResponse transfer = integrationService.getTransfer(ref);
        // A reference is a guessable handle to someone's transfer, so the
        // account it was sent from decides who may read it. The persisted
        // source account is the authority, not anything in the request.
        ownership.requireCanAccess(caller, transfer.getFromAccountId());
        return ResponseEntity.ok(transfer);
    }

    @GetMapping("/exchange-rate")
    public ResponseEntity<ExchangeRateResponse> exchangeRate(
            @RequestParam String from,
            @RequestParam String to) {
        return ResponseEntity.ok(integrationService.getExchangeRate(from, to));
    }

    @PostMapping("/convert")
    public ResponseEntity<ConvertResponse> convert(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam BigDecimal amount) {
        return ResponseEntity.ok(integrationService.convert(from, to, amount));
    }

    @GetMapping("/validate-account")
    public ResponseEntity<AccountValidationResponse> validateAccount(@RequestParam String accountNumber) {
        return ResponseEntity.ok(integrationService.validateAccount(accountNumber));
    }
}
