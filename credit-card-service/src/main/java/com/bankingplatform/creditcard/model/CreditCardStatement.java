package com.bankingplatform.creditcard.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "credit_card_statements",
        uniqueConstraints = @UniqueConstraint(columnNames = {"credit_card_id", "statement_date"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreditCardStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_card_id", nullable = false)
    private CreditCard creditCard;

    @Column(nullable = false)
    private LocalDate statementDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal closingBalance;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPurchases;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPayments;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal interestCharged;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal feesCharged;

    @Column(nullable = false)
    private Integer rewardsEarned;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal minimumPayment;

    @Column(nullable = false)
    private LocalDate paymentDueDate;

    @Column(nullable = false)
    private Boolean paidInFull;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (totalPurchases == null) totalPurchases = BigDecimal.ZERO;
        if (totalPayments == null) totalPayments = BigDecimal.ZERO;
        if (interestCharged == null) interestCharged = BigDecimal.ZERO;
        if (feesCharged == null) feesCharged = BigDecimal.ZERO;
        if (rewardsEarned == null) rewardsEarned = 0;
        if (paidInFull == null) paidInFull = false;
    }
}
