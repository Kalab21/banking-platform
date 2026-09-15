package com.bankingplatform.integration.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WireTransferRequest {
    private Long fromAccountId;
    private String beneficiaryName;
    private String beneficiaryAccount;
    private String routingNumber;
    private String bankName;
    private String bankCountry;
    private BigDecimal amount;
    private String currency;
    private String purpose;
}
