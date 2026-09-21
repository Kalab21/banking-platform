package com.bankingplatform.loan.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "loans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic locking, behind the pessimistic lock the money paths take.
     *
     * <p>Those paths read the loan {@code FOR UPDATE}, so they do not rely on
     * this. It is here for the ones that do not: a future method that reads a
     * loan, changes it and saves it without taking the lock fails on the
     * version rather than quietly overwriting a concurrent repayment.
     */
    @Version
    private Long version;

    @Column(nullable = false)
    private Long userId;

    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanType loanType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal interestRate;

    @Column(nullable = false)
    private Integer termMonths;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyPayment;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalInterest;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingBalance;

    private Long disbursementAccountId;

    private LocalDateTime disbursedAt;

    private LocalDate nextPaymentDate;

    @Column(nullable = false)
    private Integer paymentsMade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = LoanStatus.PENDING;
        if (currency == null) currency = "USD";
        if (paymentsMade == null) paymentsMade = 0;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
