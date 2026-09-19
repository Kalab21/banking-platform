package com.bankingplatform.creditcard.controller;

import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
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

    /**
     * A card owner comes from the stored card, never from the request.
     *
     * <p>A card id in a path says which card is wanted; it says nothing
     * about who is entitled to it. Every handler below resolves the owner
     * first and authorises against that, so guessing an id reaches a 403
     * rather than another customer balance, limit and statement history.
     */
    private void requireOwnsCard(CallerIdentity caller, Long cardId) {
        AccessGuard.requireOwnerOrStaff(caller, creditCardService.getCard(cardId).getUserId());
    }

    @PostMapping
    @Operation(summary = "Issue a new credit card")
    public ResponseEntity<CreditCardResponse> createCard(@Valid @RequestBody CreateCreditCardRequest request,
                                                         CallerIdentity caller) {
        // A customer may apply for their own card; staff may issue one for anyone.
        AccessGuard.requireTargetUserAllowed(caller, request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.createCard(request));
    }

    @GetMapping("/{cardId}")
    @Operation(summary = "Get credit card by ID")
    public ResponseEntity<CreditCardResponse> getCard(@PathVariable Long cardId, CallerIdentity caller) {
        CreditCardResponse card = creditCardService.getCard(cardId);
        AccessGuard.requireOwnerOrStaff(caller, card.getUserId());
        return ResponseEntity.ok(card);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all credit cards for a user")
    public ResponseEntity<List<CreditCardResponse>> getCardsByUser(@PathVariable Long userId,
                                                                   CallerIdentity caller) {
        AccessGuard.requireTargetUserAllowed(caller, userId);
        return ResponseEntity.ok(creditCardService.getCardsByUser(userId));
    }

    @PutMapping("/{cardId}/status")
    @Operation(summary = "Freeze, unfreeze, or close a card")
    public ResponseEntity<CreditCardResponse> updateStatus(@PathVariable Long cardId,
                                                             @Valid @RequestBody UpdateCardStatusRequest request,
                                                             CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return ResponseEntity.ok(creditCardService.updateStatus(cardId, request));
    }

    @PostMapping("/{cardId}/purchase")
    @Operation(summary = "Make a purchase")
    public ResponseEntity<CreditCardTransactionResponse> purchase(@PathVariable Long cardId,
                                                                   @Valid @RequestBody PurchaseRequest request,
                                                                   CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.purchase(cardId, request));
    }

    @PostMapping("/{cardId}/cash-advance")
    @Operation(summary = "Take a cash advance")
    public ResponseEntity<CreditCardTransactionResponse> cashAdvance(@PathVariable Long cardId,
                                                                      @Valid @RequestBody CashAdvanceRequest request,
                                                                      CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.cashAdvance(cardId, request));
    }

    @PostMapping("/{cardId}/payment")
    @Operation(summary = "Make a payment against balance")
    public ResponseEntity<CreditCardTransactionResponse> makePayment(@PathVariable Long cardId,
                                                                      @Valid @RequestBody CardPaymentRequest request,
                                                                      CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.makePayment(cardId, request));
    }

    @GetMapping("/{cardId}/transactions")
    @Operation(summary = "Get transaction history")
    public ResponseEntity<Page<CreditCardTransactionResponse>> getTransactions(
            @PathVariable Long cardId,
            @PageableDefault(size = 20) Pageable pageable,
            CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return ResponseEntity.ok(creditCardService.getTransactions(cardId, pageable));
    }

    @GetMapping("/transactions/{transactionRef}")
    @Operation(summary = "Get transaction by reference")
    public ResponseEntity<CreditCardTransactionResponse> getTransaction(@PathVariable String transactionRef,
                                                                        CallerIdentity caller) {
        // A reference is a guessable handle, so the card behind it decides
        // who may read it.
        CreditCardTransactionResponse transaction = creditCardService.getTransaction(transactionRef);
        requireOwnsCard(caller, transaction.getCreditCardId());
        return ResponseEntity.ok(transaction);
    }

    @PostMapping("/{cardId}/statements/generate")
    @Operation(summary = "Manually trigger statement generation")
    public ResponseEntity<CreditCardStatementResponse> generateStatement(@PathVariable Long cardId,
                                                                         CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.generateStatement(cardId));
    }

    @GetMapping("/{cardId}/statements")
    @Operation(summary = "Get all statements for a card")
    public ResponseEntity<List<CreditCardStatementResponse>> getStatements(@PathVariable Long cardId,
                                                                           CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return ResponseEntity.ok(creditCardService.getStatements(cardId));
    }
}
