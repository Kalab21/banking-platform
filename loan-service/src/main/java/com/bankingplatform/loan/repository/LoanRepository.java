package com.bankingplatform.loan.repository;

import com.bankingplatform.loan.model.Loan;
import com.bankingplatform.loan.model.LoanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    List<Loan> findByUserId(Long userId);
    List<Loan> findByStatus(LoanStatus status);
    List<Loan> findByUserIdAndStatus(Long userId, LoanStatus status);

    /**
     * The loan, held for the rest of the transaction.
     *
     * <p>A repayment reads the loan, picks the next unpaid instalment,
     * computes a new remaining balance from what it read, and writes all of
     * it back. Without the lock those are separate acts: two repayments
     * arriving together both read the same balance and both pick the same
     * instalment, so the customer is debited twice, one instalment absorbs
     * both, and the loan is reduced by one payment.
     *
     * <p>Pessimistic rather than optimistic for the same reason as the card:
     * detecting the collision afterwards and retrying means replaying money
     * movement, which is what the idempotency key exists to prevent.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Loan l WHERE l.id = :id")
    Optional<Loan> findByIdForUpdate(@Param("id") Long id);
}
