package com.bankingplatform.loan.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PayoffQuoteResponse {
    private Long loanId;
    private BigDecimal remainingBalance;
    private BigDecimal accruedInterest;
    private BigDecimal totalPayoffAmount;
    private LocalDate quoteDate;
    private Integer paymentsRemaining;
}
