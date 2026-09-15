package com.bankingplatform.integration.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SwiftTransferRequest {
    private Long fromAccountId;
    private String beneficiaryName;
    private String iban;
    private String swiftCode;
    private String bankName;
    private String bankCountry;
    private BigDecimal amount;
    private String currency;
    private String purpose;
}
