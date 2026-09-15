package com.bankingplatform.statistics.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "platform_stats")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

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

    @PreUpdate
    void preUpdate() { lastUpdated = LocalDateTime.now(); }
}
