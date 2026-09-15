package com.bankingplatform.loan.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class LoanRepaymentResponse {
    private Long id;
    private Long loanId;
    private String paymentRef;
    private BigDecimal amount;
    private BigDecimal principalPaid;
    private BigDecimal interestPaid;
    private Long sourceAccountId;
    private Integer paymentNumber;
    private Boolean isEarlyPayoff;
    private LocalDateTime createdAt;
}
