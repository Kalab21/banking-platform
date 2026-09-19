package com.bankingplatform.loan.controller;

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
                                                                CallerIdentity caller) {
        requireOwnsLoan(caller, loanId);
        return ResponseEntity.status(HttpStatus.CREATED).body(loanService.makeRepayment(loanId, request));
    }

    @PostMapping("/{loanId}/payoff")
    @Operation(summary = "Early payoff — pay off remaining balance")
    public ResponseEntity<LoanRepaymentResponse> earlyPayoff(@PathVariable Long loanId,
                                                              @Valid @RequestBody LoanRepaymentRequest request,
                                                              CallerIdentity caller) {
        requireOwnsLoan(caller, loanId);
        return ResponseEntity.status(HttpStatus.CREATED).body(loanService.earlyPayoff(loanId, request));
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
}
