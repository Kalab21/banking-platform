package com.bankingplatform.transaction.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One money-movement attempt, keyed by the client's {@code Idempotency-Key}.
 *
 * <p>Written in its own transaction, separately from the transfer it guards, so
 * that a concurrent duplicate can see the claim before the money has moved.
 * Were it written in the same transaction it would become visible only at
 * commit — by which time a second request would already have applied a second
 * debit.
 */
@Entity
@Table(name = "idempotency_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(nullable = false, length = 20)
    private String operation;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "result_ref", length = 80)
    private String resultRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onInsert() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }
}
