package com.bankingplatform.creditcard.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "credit_card_transactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreditCardTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_card_id", nullable = false)
    private CreditCard creditCard;

    @Column(nullable = false, unique = true, length = 36)
    private String transactionRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CreditCardTransactionType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    private String description;

    @Column(length = 100)
    private String merchantName;

    @Column(length = 50)
    private String merchantCategory;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (status == null) status = "COMPLETED";
    }
}
