package com.bankingplatform.transaction.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What a transfer was trying to do, recorded before it tries.
 *
 * <p>A transfer debits one account and credits another, both in
 * {@code account-service}, over HTTP. If the credit fails, the debit has
 * already been applied in another database and the local transaction cannot
 * undo it.
 *
 * <p>That case was detected and reported, and nothing recorded it — because
 * the failure rolled back the very transaction that would have written the
 * record. account-service was short the money and this service had no
 * evidence a transfer had been attempted at all.
 *
 * <p>This row is written in a transaction of its own, before the first leg,
 * so it survives that rollback. It is the only durable trace of the attempt,
 * and therefore the only thing reconciliation can start from.
 */
@Entity
@Table(name = "transfer_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The keys the two legs are applied under in account-service.
     *
     * <p>Minted before either call, so reconciliation can ask
     * account-service what became of each leg using the same key the leg was
     * applied under — rather than guessing from amounts and timestamps.
     */
    @Column(name = "debit_ref", nullable = false, unique = true, length = 80)
    private String debitRef;

    @Column(name = "credit_ref", nullable = false, length = 80)
    private String creditRef;

    @Column(name = "from_account_id", nullable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private Long toAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferAttemptStatus status;

    /** What reconciliation found, so the answer is recorded rather than re-derived. */
    @Column(name = "debit_applied")
    private Boolean debitApplied;

    @Column(name = "credit_applied")
    private Boolean creditApplied;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    @Column(length = 500)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
