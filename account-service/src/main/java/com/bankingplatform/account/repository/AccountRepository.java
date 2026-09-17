package com.bankingplatform.account.repository;

import com.bankingplatform.account.model.Account;
import com.bankingplatform.account.model.AccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Loads one account for a read-modify-write, holding a row lock until the
     * transaction commits.
     *
     * <p>Issues {@code SELECT ... FOR UPDATE}. A second transaction asking for
     * the same row waits here rather than reading the balance the first one is
     * about to change, so two debits are applied one after the other and the
     * second is checked against what the first actually left behind.
     *
     * <p>Without it, both transactions read the same starting balance, both
     * find it sufficient, and the second write silently overwrites the
     * first — an account with 100 can pay out 80 twice.
     *
     * <p>Locking is deliberately per-account: nothing here ever holds two
     * account locks at once, so there is no lock-ordering deadlock to design
     * around. A transfer takes its two locks in two separate requests and two
     * separate transactions, which is also why a transfer is still not atomic
     * across services.
     *
     * <p>Only the write paths use this. Ordinary reads take no lock, so
     * displaying a balance never waits on a payment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    List<Account> findByUserId(Long userId);

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByUserIdAndStatus(Long userId, AccountStatus status);

    boolean existsByAccountNumber(String accountNumber);
}
