package com.bankingplatform.integration.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TransferResponse {
    private Long id;
    private String transferRef;
    private String transferType;
    private Long fromAccountId;
    private String beneficiaryName;
    private String beneficiaryAccount;
    private String routingNumber;
    private String swiftCode;
    private String iban;
    private String bankName;
    private String bankCountry;
    private BigDecimal amount;
    private String currency;
    private String purpose;
    private String status;
    private LocalDate estimatedArrival;
    private LocalDateTime createdAt;
}
