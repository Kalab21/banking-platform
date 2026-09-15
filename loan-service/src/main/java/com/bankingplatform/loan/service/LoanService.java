package com.bankingplatform.loan.service;

import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.dto.request.DisburseRequest;
import com.bankingplatform.loan.dto.request.LoanRepaymentRequest;
import com.bankingplatform.loan.dto.response.*;

import java.util.List;

public interface LoanService {
    LoanResponse createLoan(CreateLoanRequest request);
    LoanResponse getLoan(Long loanId);
    List<LoanResponse> getLoansByUser(Long userId);

    LoanResponse disburseLoan(Long loanId, DisburseRequest request);

    List<AmortizationScheduleResponse> getAmortizationSchedule(Long loanId);

    LoanRepaymentResponse makeRepayment(Long loanId, LoanRepaymentRequest request);
    LoanRepaymentResponse earlyPayoff(Long loanId, LoanRepaymentRequest request);
    List<LoanRepaymentResponse> getRepayments(Long loanId);

    PayoffQuoteResponse getPayoffQuote(Long loanId);

    void markMissedPayments();
}
