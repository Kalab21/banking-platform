package com.bankingplatform.integration.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AchTransferRequest {
    private Long fromAccountId;
    private String beneficiaryName;
    private String beneficiaryAccount;
    private String routingNumber;
    private String bankName;
    private BigDecimal amount;
    private String currency;
    private String purpose;
}
