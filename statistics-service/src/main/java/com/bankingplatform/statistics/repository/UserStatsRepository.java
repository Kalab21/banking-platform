package com.bankingplatform.statistics.repository;

import com.bankingplatform.statistics.model.UserStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Optional;

public interface UserStatsRepository extends JpaRepository<UserStats, Long> {
    Optional<UserStats> findByUserId(Long userId);

    @Modifying
    @Query("UPDATE UserStats u SET u.totalAccounts = u.totalAccounts + 1, u.lastUpdated = CURRENT_TIMESTAMP WHERE u.userId = :userId")
    int incrementAccounts(Long userId);

    @Modifying
    @Query("UPDATE UserStats u SET u.totalTransactions = u.totalTransactions + 1, u.totalAmountOut = u.totalAmountOut + :amount, u.lastUpdated = CURRENT_TIMESTAMP WHERE u.userId = :userId")
    int incrementTransactionsOut(Long userId, BigDecimal amount);

    @Modifying
    @Query("UPDATE UserStats u SET u.totalPayments = u.totalPayments + 1, u.totalPaymentVolume = u.totalPaymentVolume + :amount, u.lastUpdated = CURRENT_TIMESTAMP WHERE u.userId = :userId")
    int incrementPayments(Long userId, BigDecimal amount);

    @Modifying
    @Query("UPDATE UserStats u SET u.activeLoans = u.activeLoans + 1, u.totalLoanAmount = u.totalLoanAmount + :amount, u.lastUpdated = CURRENT_TIMESTAMP WHERE u.userId = :userId")
    int incrementLoans(Long userId, BigDecimal amount);

    @Modifying
    @Query("UPDATE UserStats u SET u.activeLoans = GREATEST(u.activeLoans - 1, 0), u.lastUpdated = CURRENT_TIMESTAMP WHERE u.userId = :userId")
    int decrementActiveLoans(Long userId);

    @Modifying
    @Query("UPDATE UserStats u SET u.ccTransactions = u.ccTransactions + 1, u.ccSpend = u.ccSpend + :amount, u.lastUpdated = CURRENT_TIMESTAMP WHERE u.userId = :userId")
    int incrementCcTransactions(Long userId, BigDecimal amount);
}
