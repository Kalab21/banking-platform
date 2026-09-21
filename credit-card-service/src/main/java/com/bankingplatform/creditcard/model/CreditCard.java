package com.bankingplatform.creditcard.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "credit_cards")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreditCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic locking, behind the pessimistic lock the money paths take.
     *
     * <p>Those paths read the card {@code FOR UPDATE}, so they do not rely on
     * this. It is here for the ones that do not: a future method that reads a
     * card, changes it and saves it without taking the lock will fail on the
     * version rather than quietly overwrite a concurrent balance change.
     */
    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 16)
    private String cardNumber;

    @Column(nullable = false)
    private Long userId;

    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardType cardType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal availableCredit;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal currentBalance;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal statementBalance;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal minimumPaymentDue;

    private LocalDate paymentDueDate;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal apr;

    @Column(nullable = false, precision = 12, scale = 10)
    private BigDecimal dailyRate;

    @Column(nullable = false)
    private Integer billingCycleDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Integer rewardsPoints;

    private Long linkedAccountId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (cardType == null) cardType = CardType.STANDARD;
        if (status == null) status = CardStatus.ACTIVE;
        if (currency == null) currency = "USD";
        if (rewardsPoints == null) rewardsPoints = 0;
        if (billingCycleDay == null) billingCycleDay = 1;
        if (currentBalance == null) currentBalance = BigDecimal.ZERO;
        if (statementBalance == null) statementBalance = BigDecimal.ZERO;
        if (minimumPaymentDue == null) minimumPaymentDue = BigDecimal.ZERO;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
