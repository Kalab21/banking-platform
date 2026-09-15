package com.bankingplatform.statistics.service;

import com.bankingplatform.statistics.dto.DailySnapshotResponse;
import com.bankingplatform.statistics.dto.PlatformStatsResponse;
import com.bankingplatform.statistics.dto.UserStatsResponse;

import java.time.LocalDate;
import java.util.List;

public interface StatisticsService {
    PlatformStatsResponse getPlatformStats();
    UserStatsResponse getUserStats(Long userId);
    DailySnapshotResponse getDailySnapshot(LocalDate date);
    List<DailySnapshotResponse> getDailySnapshots(LocalDate from, LocalDate to);

    void onAccountCreated(Long userId);
    void onTransactionCreated(Long payerUserId, java.math.BigDecimal amount);
    void onPaymentCompleted(Long payerUserId, java.math.BigDecimal amount);
    void onPaymentFailed();
    void onApplicationSubmitted();
    void onApplicationApproved();
    void onApplicationRejected();
    void onCreditCardCreated(Long userId);
    void onCcTransaction(Long userId, java.math.BigDecimal amount);
    void onLoanDisbursed(Long userId, java.math.BigDecimal amount);
    void onLoanRepayment(java.math.BigDecimal amount);
    void onLoanPaidOff(Long userId);
}
