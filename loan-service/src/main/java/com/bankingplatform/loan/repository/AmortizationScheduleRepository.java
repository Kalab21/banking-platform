package com.bankingplatform.loan.repository;

import com.bankingplatform.loan.model.AmortizationSchedule;
import com.bankingplatform.loan.model.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AmortizationScheduleRepository extends JpaRepository<AmortizationSchedule, Long> {
    List<AmortizationSchedule> findByLoanIdOrderByPaymentNumber(Long loanId);
    Optional<AmortizationSchedule> findByLoanIdAndPaymentNumber(Long loanId, Integer paymentNumber);

    @Query("SELECT s FROM AmortizationSchedule s WHERE s.status = 'PENDING' AND s.dueDate <= :date")
    List<AmortizationSchedule> findDuePayments(LocalDate date);

    List<AmortizationSchedule> findByLoanIdAndStatus(Long loanId, ScheduleStatus status);
}
