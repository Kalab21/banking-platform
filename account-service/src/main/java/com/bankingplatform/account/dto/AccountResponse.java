package com.bankingplatform.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {

    private Long id;
    private String accountNumber;
    private Long userId;
    private String accountType;
    private String status;
    private BigDecimal balance;
    private String currency;
    private BigDecimal interestRate;
    private BigDecimal overdraftLimit;
    private BigDecimal overdraftBalance;
    private BigDecimal availableBalance;
    private LocalDateTime createdAt;
}
