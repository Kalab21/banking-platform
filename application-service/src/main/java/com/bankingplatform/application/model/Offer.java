package com.bankingplatform.application.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What Northbank offered, and what the customer did about it.
 *
 * <p>The terms are set once, when the offer is made, and there is no setter for
 * any of them. A customer accepts or declines an offer; they do not accept a
 * <em>modified</em> offer, and there is deliberately no code path through which
 * an acceptance could carry a different amount, rate or term than the one that
 * was made. The only mutable things here are the status and the two timestamps
 * that record what happened to it.
 *
 * <p>The accepted offer is the source of truth for the product. Before this
 * existed, {@code loan-service} chose the term from a switch statement on
 * product type — so a customer who asked for twelve months was written
 * forty-eight, and nothing in the system recorded that they had been offered
 * anything else.
 */
@Entity
@Table(name = "offers")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Offer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    /** The decision this offer came out of, so terms trace back to their reasons. */
    @Column(name = "decision_snapshot_id", updatable = false)
    private Long decisionSnapshotId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, updatable = false, length = 32)
    private ApplicationType productType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.OFFERED;

    // ---- the terms, none of them updatable -------------------------------

    @Column(name = "approved_amount", updatable = false, precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    @Column(updatable = false, precision = 9, scale = 4)
    private BigDecimal apr;

    @Column(name = "term_months", updatable = false)
    private Integer termMonths;

    @Column(name = "monthly_payment", updatable = false, precision = 19, scale = 2)
    private BigDecimal monthlyPayment;

    @Column(name = "credit_limit", updatable = false, precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "card_tier", updatable = false, length = 16)
    private String cardTier;

    @Column(nullable = false, updatable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    // ---- what happened to it ---------------------------------------------

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "declined_at")
    private LocalDateTime declinedAt;

    /**
     * Two customers clicking accept at the same moment both read an OFFERED
     * row. Without this, both write ACCEPTED and two provisioning requests go
     * out for one offer. The version makes the second write fail rather than
     * silently win.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    public enum Status { OFFERED, ACCEPTED, DECLINED, EXPIRED }

    public boolean isOpen() {
        return status == Status.OFFERED;
    }

    public boolean hasExpired(LocalDateTime now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /** Accepting is the only thing that sets these, and only from OFFERED. */
    public void accept(LocalDateTime when) {
        this.status = Status.ACCEPTED;
        this.acceptedAt = when;
    }

    public void decline(LocalDateTime when) {
        this.status = Status.DECLINED;
        this.declinedAt = when;
    }

    public void expire() {
        this.status = Status.EXPIRED;
    }
}
