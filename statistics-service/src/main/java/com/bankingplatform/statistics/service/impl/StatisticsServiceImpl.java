package com.bankingplatform.statistics.service.impl;

import com.bankingplatform.statistics.dto.DailySnapshotResponse;
import com.bankingplatform.statistics.dto.PlatformStatsResponse;
import com.bankingplatform.statistics.dto.UserStatsResponse;
import com.bankingplatform.statistics.model.DailySnapshot;
import com.bankingplatform.statistics.model.PlatformStats;
import com.bankingplatform.statistics.model.UserStats;
import com.bankingplatform.statistics.repository.DailySnapshotRepository;
import com.bankingplatform.statistics.repository.PlatformStatsRepository;
import com.bankingplatform.statistics.repository.UserStatsRepository;
import com.bankingplatform.statistics.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatisticsServiceImpl implements StatisticsService {

    private final PlatformStatsRepository platformRepo;
    private final UserStatsRepository userRepo;
    private final DailySnapshotRepository snapshotRepo;

    // ---- READ (cached) ----

    @Override
    @Cacheable(value = "platform-stats", key = "'global'")
    @Transactional(readOnly = true)
    public PlatformStatsResponse getPlatformStats() {
        PlatformStats s = platformRepo.findAll().get(0);
        return mapPlatform(s);
    }

    @Override
    @Cacheable(value = "user-stats", key = "#userId")
    @Transactional(readOnly = true)
    public UserStatsResponse getUserStats(Long userId) {
        return userRepo.findByUserId(userId)
                .map(this::mapUser)
                .orElseGet(() -> emptyUserStats(userId));
    }

    @Override
    @Cacheable(value = "daily-snapshots", key = "#date")
    @Transactional(readOnly = true)
    public DailySnapshotResponse getDailySnapshot(LocalDate date) {
        return snapshotRepo.findBySnapshotDate(date)
                .map(this::mapSnapshot)
                .orElseGet(() -> emptySnapshot(date));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailySnapshotResponse> getDailySnapshots(LocalDate from, LocalDate to) {
        return snapshotRepo.findBySnapshotDateBetweenOrderBySnapshotDateDesc(from, to).stream()
                .map(this::mapSnapshot)
                .toList();
    }

    // ---- WRITE (evict cache) ----

    @Override
    @Transactional
    @CacheEvict(value = "platform-stats", key = "'global'")
    public void onAccountCreated(Long userId) {
        platformRepo.incrementAccounts();
        snapshotRepo.upsertAccount(LocalDate.now());
        ensureUserStats(userId);
        userRepo.incrementAccounts(userId);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "platform-stats", key = "'global'"),
        @CacheEvict(value = "user-stats", key = "#payerUserId", condition = "#payerUserId != null")
    })
    public void onTransactionCreated(Long payerUserId, BigDecimal amount) {
        platformRepo.incrementTransactions(amount);
        snapshotRepo.upsertTransaction(LocalDate.now(), amount);
        if (payerUserId != null) {
            ensureUserStats(payerUserId);
            userRepo.incrementTransactionsOut(payerUserId, amount);
        }
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "platform-stats", key = "'global'"),
        @CacheEvict(value = "user-stats", key = "#payerUserId", condition = "#payerUserId != null")
    })
    public void onPaymentCompleted(Long payerUserId, BigDecimal amount) {
        platformRepo.incrementPayments(amount);
        snapshotRepo.upsertPayment(LocalDate.now(), amount);
        if (payerUserId != null) {
            ensureUserStats(payerUserId);
            userRepo.incrementPayments(payerUserId, amount);
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = "platform-stats", key = "'global'")
    public void onPaymentFailed() {
        platformRepo.incrementFailedPayments();
    }

    @Override
    @Transactional
    @CacheEvict(value = "platform-stats", key = "'global'")
    public void onApplicationSubmitted() {
        platformRepo.incrementApplications();
        snapshotRepo.upsertApplication(LocalDate.now());
    }

    @Override
    @Transactional
    @CacheEvict(value = "platform-stats", key = "'global'")
    public void onApplicationApproved() {
        platformRepo.incrementApprovedApplications();
        snapshotRepo.upsertApprovedApplication(LocalDate.now());
    }

    @Override
    @Transactional
    @CacheEvict(value = "platform-stats", key = "'global'")
    public void onApplicationRejected() {
        platformRepo.incrementRejectedApplications();
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "platform-stats", key = "'global'"),
        @CacheEvict(value = "user-stats", key = "#userId")
    })
    public void onCreditCardCreated(Long userId) {
        platformRepo.incrementCreditCards();
        ensureUserStats(userId);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "platform-stats", key = "'global'"),
        @CacheEvict(value = "user-stats", key = "#userId")
    })
    public void onCcTransaction(Long userId, BigDecimal amount) {
        platformRepo.incrementCcTransactions(amount);
        snapshotRepo.upsertCcTransaction(LocalDate.now(), amount);
        if (userId != null) {
            ensureUserStats(userId);
            userRepo.incrementCcTransactions(userId, amount);
        }
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "platform-stats", key = "'global'"),
        @CacheEvict(value = "user-stats", key = "#userId")
    })
    public void onLoanDisbursed(Long userId, BigDecimal amount) {
        platformRepo.incrementLoans(amount);
        snapshotRepo.upsertLoan(LocalDate.now(), amount);
        ensureUserStats(userId);
        userRepo.incrementLoans(userId, amount);
    }

    @Override
    @Transactional
    @CacheEvict(value = "platform-stats", key = "'global'")
    public void onLoanRepayment(BigDecimal amount) {
        platformRepo.incrementRepayments(amount);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "platform-stats", key = "'global'"),
        @CacheEvict(value = "user-stats", key = "#userId")
    })
    public void onLoanPaidOff(Long userId) {
        platformRepo.incrementLoansPaidOff();
        if (userId != null) {
            ensureUserStats(userId);
            userRepo.decrementActiveLoans(userId);
        }
    }

    // ---- helpers ----

    private void ensureUserStats(Long userId) {
        if (!userRepo.findByUserId(userId).isPresent()) {
            userRepo.save(UserStats.builder().userId(userId).build());
        }
    }

    private PlatformStatsResponse mapPlatform(PlatformStats s) {
        PlatformStatsResponse r = new PlatformStatsResponse();
        r.setTotalAccounts(s.getTotalAccounts());
        r.setTotalTransactions(s.getTotalTransactions());
        r.setTransactionVolume(s.getTransactionVolume());
        r.setTotalPayments(s.getTotalPayments());
        r.setPaymentVolume(s.getPaymentVolume());
        r.setFailedPayments(s.getFailedPayments());
        r.setTotalApplications(s.getTotalApplications());
        r.setApprovedApplications(s.getApprovedApplications());
        r.setRejectedApplications(s.getRejectedApplications());
        r.setTotalCreditCards(s.getTotalCreditCards());
        r.setCcTransactions(s.getCcTransactions());
        r.setCcSpend(s.getCcSpend());
        r.setTotalLoans(s.getTotalLoans());
        r.setTotalLoanDisbursed(s.getTotalLoanDisbursed());
        r.setTotalRepayments(s.getTotalRepayments());
        r.setTotalRepaymentVolume(s.getTotalRepaymentVolume());
        r.setLoansPaidOff(s.getLoansPaidOff());
        r.setLastUpdated(s.getLastUpdated());
        return r;
    }

    private UserStatsResponse mapUser(UserStats s) {
        UserStatsResponse r = new UserStatsResponse();
        r.setUserId(s.getUserId());
        r.setTotalTransactions(s.getTotalTransactions());
        r.setTotalAmountIn(s.getTotalAmountIn());
        r.setTotalAmountOut(s.getTotalAmountOut());
        r.setTotalPayments(s.getTotalPayments());
        r.setTotalPaymentVolume(s.getTotalPaymentVolume());
        r.setTotalAccounts(s.getTotalAccounts());
        r.setActiveLoans(s.getActiveLoans());
        r.setTotalLoanAmount(s.getTotalLoanAmount());
        r.setCcTransactions(s.getCcTransactions());
        r.setCcSpend(s.getCcSpend());
        r.setLastUpdated(s.getLastUpdated());
        return r;
    }

    private DailySnapshotResponse mapSnapshot(DailySnapshot s) {
        DailySnapshotResponse r = new DailySnapshotResponse();
        r.setSnapshotDate(s.getSnapshotDate());
        r.setNewAccounts(s.getNewAccounts());
        r.setNewTransactions(s.getNewTransactions());
        r.setTransactionVolume(s.getTransactionVolume());
        r.setNewPayments(s.getNewPayments());
        r.setPaymentVolume(s.getPaymentVolume());
        r.setNewApplications(s.getNewApplications());
        r.setApprovedApplications(s.getApprovedApplications());
        r.setNewLoans(s.getNewLoans());
        r.setLoanVolume(s.getLoanVolume());
        r.setCcTransactions(s.getCcTransactions());
        r.setCcSpend(s.getCcSpend());
        return r;
    }

    private UserStatsResponse emptyUserStats(Long userId) {
        UserStatsResponse r = new UserStatsResponse();
        r.setUserId(userId);
        r.setTotalTransactions(0L);
        r.setTotalAmountIn(BigDecimal.ZERO);
        r.setTotalAmountOut(BigDecimal.ZERO);
        r.setTotalPayments(0L);
        r.setTotalPaymentVolume(BigDecimal.ZERO);
        r.setTotalAccounts(0);
        r.setActiveLoans(0);
        r.setTotalLoanAmount(BigDecimal.ZERO);
        r.setCcTransactions(0L);
        r.setCcSpend(BigDecimal.ZERO);
        return r;
    }

    private DailySnapshotResponse emptySnapshot(LocalDate date) {
        DailySnapshotResponse r = new DailySnapshotResponse();
        r.setSnapshotDate(date);
        r.setNewAccounts(0);
        r.setNewTransactions(0);
        r.setTransactionVolume(BigDecimal.ZERO);
        r.setNewPayments(0);
        r.setPaymentVolume(BigDecimal.ZERO);
        r.setNewApplications(0);
        r.setApprovedApplications(0);
        r.setNewLoans(0);
        r.setLoanVolume(BigDecimal.ZERO);
        r.setCcTransactions(0);
        r.setCcSpend(BigDecimal.ZERO);
        return r;
    }
}
