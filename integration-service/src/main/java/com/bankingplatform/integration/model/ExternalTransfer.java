package com.bankingplatform.integration.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "external_transfers")
public class ExternalTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_ref", nullable = false, unique = true, length = 36)
    private String transferRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false, length = 10)
    private TransferType transferType;

    @Column(name = "from_account_id", nullable = false)
    private Long fromAccountId;

    @Column(name = "beneficiary_name", nullable = false, length = 100)
    private String beneficiaryName;

    @Column(name = "beneficiary_account", length = 34)
    private String beneficiaryAccount;

    @Column(name = "routing_number", length = 9)
    private String routingNumber;

    @Column(name = "swift_code", length = 11)
    private String swiftCode;

    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "bank_country", length = 3)
    private String bankCountry;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String purpose;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "estimated_arrival")
    private LocalDate estimatedArrival;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }
}
