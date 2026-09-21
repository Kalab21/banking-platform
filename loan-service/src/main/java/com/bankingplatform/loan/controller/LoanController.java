package com.bankingplatform.loan.controller;

import com.bankingplatform.common.idempotency.IdempotencyGuard;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.Map;
import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.dto.request.DisburseRequest;
import com.bankingplatform.loan.dto.request.LoanRepaymentRequest;
import com.bankingplatform.loan.dto.response.*;
import com.bankingplatform.loan.service.LoanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
@Tag(name = "Loans")
public class LoanController {

    private final LoanService loanService;
    private final IdempotencyGuard idempotency;

    /**
     * A loan's owner comes from the stored loan, never from the request.
     *
     * <p>A loan id in a path says which loan is wanted; it says nothing about
     * who is entitled to it. Every handler below resolves the owner first and
     * authorises against that, so guessing an id reaches a 403 rather than
     * another customer's balance.
     */
    private Long ownerOf(Long loanId) {
        return loanService.getLoan(loanId).getUserId();
    }

    private static final String REPAYMENT = "REPAYMENT";
    private static final String PAYOFF = "PAYOFF";

    private static final String KEY_DESCRIPTION =
            "Opaque client-generated value naming this logical operation. Reuse it to retry the "
                    + "same operation safely; use a new one for a new operation.";

    private void requireOwnsLoan(CallerIdentity caller, Long loanId) {
        AccessGuard.requireOwnerOrStaff(caller, ownerOf(loanId));
    }

    @PostMapping
    @Operation(summary = "Create a new loan")
    public ResponseEntity<LoanResponse> createLoan(@Valid @RequestBody CreateLoanRequest request,
                                                   CallerIdentity caller) {
        // A customer may borrow for themselves; staff may open a loan for anyone.
        AccessGuard.requireTargetUserAllowed(caller, request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(loanService.createLoan(request));
    }

    @GetMapping("/{loanId}")
    @Operation(summary = "Get loan by ID")
    public ResponseEntity<LoanResponse> getLoan(@PathVariable Long loanId, CallerIdentity caller) {
        LoanResponse loan = loanService.getLoan(loanId);
        AccessGuard.requireOwnerOrStaff(caller, loan.getUserId());
        return ResponseEntity.ok(loan);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all loans for a user")
    public ResponseEntity<List<LoanResponse>> getLoansByUser(@PathVariable Long userId,
                                                             CallerIdentity caller) {
        AccessGuard.requireTargetUserAllowed(caller, userId);
        return ResponseEntity.ok(loanService.getLoansByUser(userId));
    }

    @PostMapping("/{loanId}/disburse")
    @Operation(summary = "Disburse loan funds to account")
    public ResponseEntity<LoanResponse> disburseLoan(@PathVariable Long loanId,
                                                      @Valid @RequestBody DisburseRequest request,
                                                      CallerIdentity caller) {
        requireOwnsLoan(caller, loanId);
        return ResponseEntity.ok(loanService.disburseLoan(loanId, request));
    }

    @GetMapping("/{loanId}/schedule")
    @Operation(summary = "Get amortization schedule")
    public ResponseEntity<List<AmortizationScheduleResponse>> getSchedule(@PathVariable Long loanId,
                                                                          CallerIdentity caller) {
        requireOwnsLoan(caller, loanId);
        return ResponseEntity.ok(loanService.getAmortizationSchedule(loanId));
    }

    @PostMapping("/{loanId}/repay")
    @Operation(summary = "Make a regular repayment")
    public ResponseEntity<LoanRepaymentResponse> makeRepayment(@PathVariable Long loanId,
                                                                @Valid @RequestBody LoanRepaymentRequest request,
                                                                @Parameter(description = KEY_DESCRIPTION)
                                                                @RequestHeader(name = IdempotencyGuard.HEADER,
                                                                        required = false) String idempotencyKey,
                                                                CallerIdentity caller) {
        // Ownership first, then the guard. A refused request must neither
        // claim a key nor leave a cached result behind it.
        requireOwnsLoan(caller, loanId);
        return idempotency.execute(idempotencyKey, REPAYMENT, caller, keyed(loanId, request),
                LoanRepaymentResponse.class, LoanRepaymentResponse::getPaymentRef,
                () -> loanService.makeRepayment(loanId, request));
    }

    @PostMapping("/{loanId}/payoff")
    @Operation(summary = "Early payoff — pay off remaining balance")
    public ResponseEntity<LoanRepaymentResponse> earlyPayoff(@PathVariable Long loanId,
                                                              @Valid @RequestBody LoanRepaymentRequest request,
                                                              @Parameter(description = KEY_DESCRIPTION)
                                                              @RequestHeader(name = IdempotencyGuard.HEADER,
                                                                      required = false) String idempotencyKey,
                                                              CallerIdentity caller) {
        requireOwnsLoan(caller, loanId);
        return idempotency.execute(idempotencyKey, PAYOFF, caller, keyed(loanId, request),
                LoanRepaymentResponse.class, LoanRepaymentResponse::getPaymentRef,
                () -> loanService.earlyPayoff(loanId, request));
    }

    @GetMapping("/{loanId}/repayments")
    @Operation(summary = "Get repayment history")
    public ResponseEntity<List<LoanRepaymentResponse>> getRepayments(@PathVariable Long loanId,
                                                                     CallerIdentity caller) {
        requireOwnsLoan(caller, loanId);
        return ResponseEntity.ok(loanService.getRepayments(loanId));
    }

    @GetMapping("/{loanId}/payoff-quote")
    @Operation(summary = "Get early payoff quote")
    public ResponseEntity<PayoffQuoteResponse> getPayoffQuote(@PathVariable Long loanId,
                                                              CallerIdentity caller) {
        requireOwnsLoan(caller, loanId);
        return ResponseEntity.ok(loanService.getPayoffQuote(loanId));
    }

    /**
     * The request as fingerprinted: the body plus the loan it is against.
     *
     * <p>The loan id is a path variable, so a fingerprint over the body alone
     * would make the same repayment on two different loans look like one
     * request. A replay would then be served the first loan's receipt and the
     * second loan would never be paid.
     */
    private Map<String, Object> keyed(Long loanId, Object request) {
        return Map.of("loanId", loanId, "request", request);
    }
}
