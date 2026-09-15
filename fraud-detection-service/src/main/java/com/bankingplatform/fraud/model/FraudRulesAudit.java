package com.bankingplatform.fraud.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "fraud_rules_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FraudRulesAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 100)
    private String ruleName;

    @Column(nullable = false)
    private int pointsAdded;

    @Column(nullable = false)
    private int totalScore;

    @Column(length = 100)
    private String eventRef;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
