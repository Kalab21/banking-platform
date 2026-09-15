package com.bankingplatform.statistics.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserStatsResponse implements Serializable {
    private Long userId;
    private Long totalTransactions;
    private BigDecimal totalAmountIn;
    private BigDecimal totalAmountOut;
    private Long totalPayments;
    private BigDecimal totalPaymentVolume;
    private Integer totalAccounts;
    private Integer activeLoans;
    private BigDecimal totalLoanAmount;
    private Long ccTransactions;
    private BigDecimal ccSpend;
    private LocalDateTime lastUpdated;
}
