package com.bankingplatform.transaction.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AccountResponse {
    private Long id;
    private String accountNumber;
    private Long userId;
    private String accountType;
    private String status;
    private BigDecimal balance;
    private String currency;
    private BigDecimal overdraftLimit;
    private BigDecimal overdraftBalance;
    private BigDecimal availableBalance;
}
