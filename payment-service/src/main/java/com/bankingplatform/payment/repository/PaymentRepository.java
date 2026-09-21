package com.bankingplatform.payment.repository;

import com.bankingplatform.payment.model.Payment;
import com.bankingplatform.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByPayerAccountId(Long payerAccountId);

    Optional<Payment> findByPaymentRef(String paymentRef);

    /**
     * Ids of due payments, locked for this transaction and skipped if another
     * worker already holds them.
     *
     * <p>{@code SKIP LOCKED} is what makes a second replica useful rather
     * than duplicative: it takes the next unclaimed rows instead of blocking
     * on the ones already being claimed. Without it, every replica scans the
     * same due payments and processes all of them.
     *
     * <p>Bounded, because the previous version selected every due payment and
     * worked through them in one transaction. A backlog then meant a single
     * enormous transaction holding locks on every row in it.
     */
    @Query(value = """
            SELECT id FROM payments
            WHERE status = 'PENDING' AND scheduled_at IS NOT NULL AND scheduled_at <= :now
            ORDER BY scheduled_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDueScheduledPayments(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * Payments left PROCESSING by a worker that died mid-batch.
     *
     * <p>A claim is a status change, so it survives the process that made it
     * -- which is the point, and also the risk: nothing else would ever pick
     * these up. They are re-claimed after a grace period. Re-running one is
     * safe because the transfer it performs carries an idempotency key
     * derived from the payment reference, so the second attempt is a replay
     * rather than a second movement of money.
     */
    @Query(value = """
            SELECT id FROM payments
            WHERE status = 'PROCESSING' AND updated_at < :cutoff
            ORDER BY updated_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockStalledPayments(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * Takes ownership of the rows just locked.
     *
     * <p>The status change is the durable half of the claim. The lock above
     * only lasts as long as the claiming transaction, and each payment is
     * then processed in a transaction of its own, so without this a second
     * replica would find the rows PENDING again the moment the claim
     * committed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
               SET p.status = com.bankingplatform.payment.model.PaymentStatus.PROCESSING,
                   p.updatedAt = :now
             WHERE p.id IN :ids
            """)
    int claim(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    List<Payment> findByPayerAccountIdAndStatus(Long payerAccountId, PaymentStatus status);
}
