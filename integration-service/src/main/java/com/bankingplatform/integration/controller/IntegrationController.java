package com.bankingplatform.integration.controller;

import com.bankingplatform.integration.dto.*;
import com.bankingplatform.integration.service.IntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService integrationService;

    @PostMapping("/wire-transfer")
    public ResponseEntity<TransferResponse> wireTransfer(@RequestBody WireTransferRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(integrationService.initiateWireTransfer(req));
    }

    @PostMapping("/ach-transfer")
    public ResponseEntity<TransferResponse> achTransfer(@RequestBody AchTransferRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(integrationService.initiateAchTransfer(req));
    }

    @PostMapping("/swift-transfer")
    public ResponseEntity<TransferResponse> swiftTransfer(@RequestBody SwiftTransferRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(integrationService.initiateSwiftTransfer(req));
    }

    @GetMapping("/transfer/{ref}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable String ref) {
        return ResponseEntity.ok(integrationService.getTransfer(ref));
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
