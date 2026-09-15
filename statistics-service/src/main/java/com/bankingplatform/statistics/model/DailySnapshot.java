package com.bankingplatform.statistics.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_snapshots")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate snapshotDate;

    private Integer newAccounts;
    private Integer newTransactions;
    private BigDecimal transactionVolume;
    private Integer newPayments;
    private BigDecimal paymentVolume;
    private Integer newApplications;
    private Integer approvedApplications;
    private Integer newLoans;
    private BigDecimal loanVolume;
    private Integer ccTransactions;
    private BigDecimal ccSpend;
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (newAccounts == null) newAccounts = 0;
        if (newTransactions == null) newTransactions = 0;
        if (transactionVolume == null) transactionVolume = BigDecimal.ZERO;
        if (newPayments == null) newPayments = 0;
        if (paymentVolume == null) paymentVolume = BigDecimal.ZERO;
        if (newApplications == null) newApplications = 0;
        if (approvedApplications == null) approvedApplications = 0;
        if (newLoans == null) newLoans = 0;
        if (loanVolume == null) loanVolume = BigDecimal.ZERO;
        if (ccTransactions == null) ccTransactions = 0;
        if (ccSpend == null) ccSpend = BigDecimal.ZERO;
    }
}
