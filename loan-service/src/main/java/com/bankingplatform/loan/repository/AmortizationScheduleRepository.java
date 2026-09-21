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

    /**
     * Instalments in the order they fall due.
     *
     * <p>The ordering is the point. The caller takes the first element as
     * "the next unpaid payment", and this query had no ORDER BY at all -- so
     * which instalment a repayment was applied to came down to whatever order
     * PostgreSQL happened to return rows in. That decides the interest and
     * principal split and the payment number recorded against the repayment,
     * so an arbitrary choice there is an arbitrary allocation of the
     * customer's money.
     */
    List<AmortizationSchedule> findByLoanIdAndStatusOrderByPaymentNumberAsc(
            Long loanId, ScheduleStatus status);
}
