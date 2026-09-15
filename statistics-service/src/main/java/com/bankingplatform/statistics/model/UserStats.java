package com.bankingplatform.statistics.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_stats")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
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

    @PrePersist
    void prePersist() {
        lastUpdated = LocalDateTime.now();
        if (totalTransactions == null) totalTransactions = 0L;
        if (totalAmountIn == null) totalAmountIn = BigDecimal.ZERO;
        if (totalAmountOut == null) totalAmountOut = BigDecimal.ZERO;
        if (totalPayments == null) totalPayments = 0L;
        if (totalPaymentVolume == null) totalPaymentVolume = BigDecimal.ZERO;
        if (totalAccounts == null) totalAccounts = 0;
        if (activeLoans == null) activeLoans = 0;
        if (totalLoanAmount == null) totalLoanAmount = BigDecimal.ZERO;
        if (ccTransactions == null) ccTransactions = 0L;
        if (ccSpend == null) ccSpend = BigDecimal.ZERO;
    }

    @PreUpdate
    void preUpdate() { lastUpdated = LocalDateTime.now(); }
}
