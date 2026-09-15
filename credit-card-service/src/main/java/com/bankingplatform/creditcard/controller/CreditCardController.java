package com.bankingplatform.creditcard.controller;

import com.bankingplatform.creditcard.dto.request.*;
import com.bankingplatform.creditcard.dto.response.*;
import com.bankingplatform.creditcard.service.CreditCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/credit-cards")
@RequiredArgsConstructor
@Tag(name = "Credit Cards")
public class CreditCardController {

    private final CreditCardService creditCardService;

    @PostMapping
    @Operation(summary = "Issue a new credit card")
    public ResponseEntity<CreditCardResponse> createCard(@Valid @RequestBody CreateCreditCardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.createCard(request));
    }

    @GetMapping("/{cardId}")
    @Operation(summary = "Get credit card by ID")
    public ResponseEntity<CreditCardResponse> getCard(@PathVariable Long cardId) {
        return ResponseEntity.ok(creditCardService.getCard(cardId));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all credit cards for a user")
    public ResponseEntity<List<CreditCardResponse>> getCardsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(creditCardService.getCardsByUser(userId));
    }

    @PutMapping("/{cardId}/status")
    @Operation(summary = "Freeze, unfreeze, or close a card")
    public ResponseEntity<CreditCardResponse> updateStatus(@PathVariable Long cardId,
                                                             @Valid @RequestBody UpdateCardStatusRequest request) {
        return ResponseEntity.ok(creditCardService.updateStatus(cardId, request));
    }

    @PostMapping("/{cardId}/purchase")
    @Operation(summary = "Make a purchase")
    public ResponseEntity<CreditCardTransactionResponse> purchase(@PathVariable Long cardId,
                                                                   @Valid @RequestBody PurchaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.purchase(cardId, request));
    }

    @PostMapping("/{cardId}/cash-advance")
    @Operation(summary = "Take a cash advance")
    public ResponseEntity<CreditCardTransactionResponse> cashAdvance(@PathVariable Long cardId,
                                                                      @Valid @RequestBody CashAdvanceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.cashAdvance(cardId, request));
    }

    @PostMapping("/{cardId}/payment")
    @Operation(summary = "Make a payment against balance")
    public ResponseEntity<CreditCardTransactionResponse> makePayment(@PathVariable Long cardId,
                                                                      @Valid @RequestBody CardPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.makePayment(cardId, request));
    }

    @GetMapping("/{cardId}/transactions")
    @Operation(summary = "Get transaction history")
    public ResponseEntity<Page<CreditCardTransactionResponse>> getTransactions(
            @PathVariable Long cardId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(creditCardService.getTransactions(cardId, pageable));
    }

    @GetMapping("/transactions/{transactionRef}")
    @Operation(summary = "Get transaction by reference")
    public ResponseEntity<CreditCardTransactionResponse> getTransaction(@PathVariable String transactionRef) {
        return ResponseEntity.ok(creditCardService.getTransaction(transactionRef));
    }

    @PostMapping("/{cardId}/statements/generate")
    @Operation(summary = "Manually trigger statement generation")
    public ResponseEntity<CreditCardStatementResponse> generateStatement(@PathVariable Long cardId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.generateStatement(cardId));
    }

    @GetMapping("/{cardId}/statements")
    @Operation(summary = "Get all statements for a card")
    public ResponseEntity<List<CreditCardStatementResponse>> getStatements(@PathVariable Long cardId) {
        return ResponseEntity.ok(creditCardService.getStatements(cardId));
    }
}
