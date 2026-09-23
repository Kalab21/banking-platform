package com.bankingplatform.creditcard.controller;

import java.util.Map;
import com.bankingplatform.common.idempotency.IdempotencyGuard;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final IdempotencyGuard idempotency;

    /**
     * A card owner comes from the stored card, never from the request.
     *
     * <p>A card id in a path says which card is wanted; it says nothing
     * about who is entitled to it. Every handler below resolves the owner
     * first and authorises against that, so guessing an id reaches a 403
     * rather than another customer balance, limit and statement history.
     */
    private static final String PURCHASE = "PURCHASE";
    private static final String CASH_ADVANCE = "CASH_ADVANCE";
    private static final String CARD_PAYMENT = "CARD_PAYMENT";

    private static final String KEY_DESCRIPTION =
            "Opaque client-generated value naming this logical operation. Reuse it to retry the "
                    + "same operation safely; use a new one for a new operation.";

    private void requireOwnsCard(CallerIdentity caller, Long cardId) {
        AccessGuard.requireOwnerOrStaff(caller, creditCardService.getCard(cardId).getUserId());
    }

    // There is deliberately no endpoint here that issues a card.
    //
    // A card used to be issued by POSTing one, with the caller naming its tier,
    // its credit limit and its APR. A customer could therefore hand themselves
    // a platinum card with any limit they liked, and staff could do the same
    // for anyone, without a decision existing anywhere.
    //
    // Issuance is a consequence of an approved application. ApplicationEventConsumer
    // creates the card in-process when application-service publishes an
    // approval, so the tier and the limit are the ones the bank set. Restoring
    // a create endpoint here — for staff, for seeding, for convenience —
    // reopens the bypass whatever the role check on it says.

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
    @Operation(summary = "Freeze or unfreeze a card")
    public ResponseEntity<CreditCardResponse> updateStatus(@PathVariable Long cardId,
                                                             @Valid @RequestBody UpdateCardStatusRequest request,
                                                             CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        // Owning the card says which card; it does not say which states the
        // caller may put it in. A cardholder may freeze and unfreeze their own
        // card and nothing else — a block or a default is the bank's, and
        // staff act under a different table of moves.
        return ResponseEntity.ok(creditCardService.updateStatus(cardId, request, caller.isStaff()));
    }

    @PostMapping("/{cardId}/purchase")
    @Operation(summary = "Make a purchase")
    public ResponseEntity<CreditCardTransactionResponse> purchase(@PathVariable Long cardId,
                                                                   @Valid @RequestBody PurchaseRequest request,
                                                                   @Parameter(description = KEY_DESCRIPTION)
                                                                   @RequestHeader(name = IdempotencyGuard.HEADER,
                                                                           required = false) String idempotencyKey,
                                                                   CallerIdentity caller) {
        // A cardholder does not manufacture their own purchases. A real one
        // arrives from a merchant through a card network, and this platform has
        // neither — so this is a simulation, and left open it let a customer
        // mint spending, rewards and statement lines at will.
        //
        // It stays, because the demo needs card history to show, but behind a
        // deliberate boundary. A customer views transactions; they do not
        // invent them.
        AccessGuard.requireStaff(caller);
        requireOwnsCard(caller, cardId);
        return idempotency.execute(idempotencyKey, PURCHASE, caller, keyed(cardId, request),
                CreditCardTransactionResponse.class, CreditCardTransactionResponse::getTransactionRef,
                () -> creditCardService.purchase(cardId, request));
    }

    @PostMapping("/{cardId}/cash-advance")
    @Operation(summary = "Take a cash advance")
    public ResponseEntity<CreditCardTransactionResponse> cashAdvance(@PathVariable Long cardId,
                                                                      @Valid @RequestBody CashAdvanceRequest request,
                                                                      @Parameter(description = KEY_DESCRIPTION)
                                                                      @RequestHeader(name = IdempotencyGuard.HEADER,
                                                                              required = false) String idempotencyKey,
                                                                      CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return idempotency.execute(idempotencyKey, CASH_ADVANCE, caller, keyed(cardId, request),
                CreditCardTransactionResponse.class, CreditCardTransactionResponse::getTransactionRef,
                () -> creditCardService.cashAdvance(cardId, request));
    }

    @PostMapping("/{cardId}/payment")
    @Operation(summary = "Make a payment against balance")
    public ResponseEntity<CreditCardTransactionResponse> makePayment(@PathVariable Long cardId,
                                                                      @Valid @RequestBody CardPaymentRequest request,
                                                                      @Parameter(description = KEY_DESCRIPTION)
                                                                      @RequestHeader(name = IdempotencyGuard.HEADER,
                                                                              required = false) String idempotencyKey,
                                                                      CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return idempotency.execute(idempotencyKey, CARD_PAYMENT, caller, keyed(cardId, request),
                CreditCardTransactionResponse.class, CreditCardTransactionResponse::getTransactionRef,
                () -> creditCardService.makePayment(cardId, request));
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
    @Operation(summary = "Trigger statement generation (staff/demo only)")
    public ResponseEntity<CreditCardStatementResponse> generateStatement(@PathVariable Long cardId,
                                                                         CallerIdentity caller) {
        // A statement is something the bank issues on a cycle, not something a
        // cardholder asks for. Left open, a customer could cut a statement
        // whenever they liked and produce as many billing periods as they
        // wanted; StatementGeneratorJob owns the real schedule.
        AccessGuard.requireStaff(caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(creditCardService.generateStatement(cardId));
    }

    @GetMapping("/{cardId}/statements")
    @Operation(summary = "Get all statements for a card")
    public ResponseEntity<List<CreditCardStatementResponse>> getStatements(@PathVariable Long cardId,
                                                                           CallerIdentity caller) {
        requireOwnsCard(caller, cardId);
        return ResponseEntity.ok(creditCardService.getStatements(cardId));
    }

    /**
     * The request as fingerprinted: the body plus the card it is against.
     *
     * <p>The card id is a path variable, so a fingerprint over the body
     * alone would make the same purchase on two different cards look like
     * one request. A replay would then be served the first card's stored
     * response and the second card would never be charged.
     */
    private Map<String, Object> keyed(Long cardId, Object request) {
        return Map.of("cardId", cardId, "request", request);
    }
}
