package com.bankingplatform.payment.repository;

import com.bankingplatform.payment.model.Payment;
import com.bankingplatform.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByPayerAccountId(Long payerAccountId);

    Optional<Payment> findByPaymentRef(String paymentRef);

    @Query("SELECT p FROM Payment p WHERE p.status = 'PENDING' AND p.scheduledAt IS NOT NULL AND p.scheduledAt <= :now")
    List<Payment> findDueScheduledPayments(LocalDateTime now);

    List<Payment> findByPayerAccountIdAndStatus(Long payerAccountId, PaymentStatus status);
}
