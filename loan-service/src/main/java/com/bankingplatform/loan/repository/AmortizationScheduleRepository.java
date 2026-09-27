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

    /**
     * PENDING instalments past due on loans that have been funded. A loan
     * waiting to be disbursed has lent nothing yet, so nothing on it is late.
     */
    @Query("SELECT s FROM AmortizationSchedule s WHERE s.status = 'PENDING' AND s.dueDate <= :date "
            + "AND s.loan.status = 'ACTIVE'")
    List<AmortizationSchedule> findDuePayments(LocalDate date);

    /** Every status that still has money owed against it. */
    List<ScheduleStatus> UNPAID = List.of(ScheduleStatus.PENDING, ScheduleStatus.PARTIAL, ScheduleStatus.MISSED);

    List<AmortizationSchedule> findByLoanIdAndStatusInOrderByPaymentNumberAsc(
            Long loanId, java.util.Collection<ScheduleStatus> statuses);

    /**
     * Instalments not yet fully paid, earliest first. Partial and missed ones
     * are included: they used to drop out of the PENDING set for good, so
     * what was still owed on them was never collected.
     */
    default List<AmortizationSchedule> findUnpaid(Long loanId) {
        return findByLoanIdAndStatusInOrderByPaymentNumberAsc(loanId, UNPAID);
    }

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
