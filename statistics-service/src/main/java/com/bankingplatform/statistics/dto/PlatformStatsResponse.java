package com.bankingplatform.statistics.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PlatformStatsResponse implements Serializable {
    private Long totalAccounts;
    private Long totalTransactions;
    private BigDecimal transactionVolume;
    private Long totalPayments;
    private BigDecimal paymentVolume;
    private Long failedPayments;
    private Long totalApplications;
    private Long approvedApplications;
    private Long rejectedApplications;
    private Long totalCreditCards;
    private Long ccTransactions;
    private BigDecimal ccSpend;
    private Long totalLoans;
    private BigDecimal totalLoanDisbursed;
    private Long totalRepayments;
    private BigDecimal totalRepaymentVolume;
    private Long loansPaidOff;
    private LocalDateTime lastUpdated;
}
