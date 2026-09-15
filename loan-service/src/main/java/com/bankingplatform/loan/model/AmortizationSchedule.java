package com.bankingplatform.loan.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "amortization_schedules",
        uniqueConstraints = @UniqueConstraint(columnNames = {"loan_id", "payment_number"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AmortizationSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id", nullable = false)
    private Loan loan;

    @Column(nullable = false)
    private Integer paymentNumber;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal scheduledPayment;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principalPortion;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal interestPortion;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleStatus status;

    private LocalDateTime paidAt;

    @PrePersist
    void prePersist() {
        if (status == null) status = ScheduleStatus.PENDING;
    }
}
