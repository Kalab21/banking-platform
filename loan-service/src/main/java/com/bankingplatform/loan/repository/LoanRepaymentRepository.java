package com.bankingplatform.loan.repository;

import com.bankingplatform.loan.model.LoanRepayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, Long> {
    List<LoanRepayment> findByLoanIdOrderByCreatedAtDesc(Long loanId);
    Optional<LoanRepayment> findByPaymentRef(String paymentRef);
    boolean existsByPaymentRef(String paymentRef);
}
