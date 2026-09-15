package com.bankingplatform.application.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CreateAccountRequest {
    private Long userId;
    private String accountType;
    private BigDecimal initialDeposit;
    private String currency;
    private BigDecimal overdraftLimit;
}
