package com.bankingplatform.loan.dto.response;

import com.bankingplatform.loan.model.LoanStatus;
import com.bankingplatform.loan.model.LoanType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LoanResponse {
    private Long id;
    private Long userId;
    private Long applicationId;
    private LoanType loanType;
    private BigDecimal principal;
    private BigDecimal interestRate;
    private Integer termMonths;
    private BigDecimal monthlyPayment;
    private BigDecimal totalInterest;
    private BigDecimal remainingBalance;
    private Long disbursementAccountId;
    private LocalDateTime disbursedAt;
    private LocalDate nextPaymentDate;
    private Integer paymentsMade;
    private LoanStatus status;
    private String currency;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
