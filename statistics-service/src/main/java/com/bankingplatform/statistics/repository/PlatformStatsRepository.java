package com.bankingplatform.statistics.repository;

import com.bankingplatform.statistics.model.PlatformStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface PlatformStatsRepository extends JpaRepository<PlatformStats, Integer> {

    @Modifying
    @Query("UPDATE PlatformStats p SET p.totalAccounts = p.totalAccounts + 1, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementAccounts();

    @Modifying
    @Query("UPDATE PlatformStats p SET p.totalTransactions = p.totalTransactions + 1, p.transactionVolume = p.transactionVolume + :amount, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementTransactions(BigDecimal amount);

    @Modifying
    @Query("UPDATE PlatformStats p SET p.totalPayments = p.totalPayments + 1, p.paymentVolume = p.paymentVolume + :amount, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementPayments(BigDecimal amount);

    @Modifying
    @Query("UPDATE PlatformStats p SET p.failedPayments = p.failedPayments + 1, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementFailedPayments();

    @Modifying
    @Query("UPDATE PlatformStats p SET p.totalApplications = p.totalApplications + 1, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementApplications();

    @Modifying
    @Query("UPDATE PlatformStats p SET p.approvedApplications = p.approvedApplications + 1, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementApprovedApplications();

    @Modifying
    @Query("UPDATE PlatformStats p SET p.rejectedApplications = p.rejectedApplications + 1, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementRejectedApplications();

    @Modifying
    @Query("UPDATE PlatformStats p SET p.totalCreditCards = p.totalCreditCards + 1, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementCreditCards();

    @Modifying
    @Query("UPDATE PlatformStats p SET p.ccTransactions = p.ccTransactions + 1, p.ccSpend = p.ccSpend + :amount, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementCcTransactions(BigDecimal amount);

    @Modifying
    @Query("UPDATE PlatformStats p SET p.totalLoans = p.totalLoans + 1, p.totalLoanDisbursed = p.totalLoanDisbursed + :amount, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementLoans(BigDecimal amount);

    @Modifying
    @Query("UPDATE PlatformStats p SET p.totalRepayments = p.totalRepayments + 1, p.totalRepaymentVolume = p.totalRepaymentVolume + :amount, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementRepayments(BigDecimal amount);

    @Modifying
    @Query("UPDATE PlatformStats p SET p.loansPaidOff = p.loansPaidOff + 1, p.lastUpdated = CURRENT_TIMESTAMP")
    void incrementLoansPaidOff();
}
