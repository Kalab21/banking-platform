package com.bankingplatform.statistics.repository;

import com.bankingplatform.statistics.model.DailySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailySnapshotRepository extends JpaRepository<DailySnapshot, Long> {
    Optional<DailySnapshot> findBySnapshotDate(LocalDate date);
    List<DailySnapshot> findBySnapshotDateBetweenOrderBySnapshotDateDesc(LocalDate from, LocalDate to);

    @Modifying
    @Query(value = """
        INSERT INTO daily_snapshots (snapshot_date, new_accounts)
        VALUES (:date, 1)
        ON CONFLICT (snapshot_date) DO UPDATE
        SET new_accounts = daily_snapshots.new_accounts + 1
        """, nativeQuery = true)
    void upsertAccount(LocalDate date);

    @Modifying
    @Query(value = """
        INSERT INTO daily_snapshots (snapshot_date, new_transactions, transaction_volume)
        VALUES (:date, 1, :amount)
        ON CONFLICT (snapshot_date) DO UPDATE
        SET new_transactions = daily_snapshots.new_transactions + 1,
            transaction_volume = daily_snapshots.transaction_volume + :amount
        """, nativeQuery = true)
    void upsertTransaction(LocalDate date, BigDecimal amount);

    @Modifying
    @Query(value = """
        INSERT INTO daily_snapshots (snapshot_date, new_payments, payment_volume)
        VALUES (:date, 1, :amount)
        ON CONFLICT (snapshot_date) DO UPDATE
        SET new_payments = daily_snapshots.new_payments + 1,
            payment_volume = daily_snapshots.payment_volume + :amount
        """, nativeQuery = true)
    void upsertPayment(LocalDate date, BigDecimal amount);

    @Modifying
    @Query(value = """
        INSERT INTO daily_snapshots (snapshot_date, new_applications, approved_applications)
        VALUES (:date, 1, 0)
        ON CONFLICT (snapshot_date) DO UPDATE
        SET new_applications = daily_snapshots.new_applications + 1
        """, nativeQuery = true)
    void upsertApplication(LocalDate date);

    @Modifying
    @Query(value = """
        INSERT INTO daily_snapshots (snapshot_date, approved_applications)
        VALUES (:date, 1)
        ON CONFLICT (snapshot_date) DO UPDATE
        SET approved_applications = daily_snapshots.approved_applications + 1
        """, nativeQuery = true)
    void upsertApprovedApplication(LocalDate date);

    @Modifying
    @Query(value = """
        INSERT INTO daily_snapshots (snapshot_date, new_loans, loan_volume)
        VALUES (:date, 1, :amount)
        ON CONFLICT (snapshot_date) DO UPDATE
        SET new_loans = daily_snapshots.new_loans + 1,
            loan_volume = daily_snapshots.loan_volume + :amount
        """, nativeQuery = true)
    void upsertLoan(LocalDate date, BigDecimal amount);

    @Modifying
    @Query(value = """
        INSERT INTO daily_snapshots (snapshot_date, cc_transactions, cc_spend)
        VALUES (:date, 1, :amount)
        ON CONFLICT (snapshot_date) DO UPDATE
        SET cc_transactions = daily_snapshots.cc_transactions + 1,
            cc_spend = daily_snapshots.cc_spend + :amount
        """, nativeQuery = true)
    void upsertCcTransaction(LocalDate date, BigDecimal amount);
}
