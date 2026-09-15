package com.bankingplatform.account.dto;

import com.bankingplatform.account.model.AccountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateAccountRequest {

    @NotNull
    private Long userId;

    @NotNull
    private AccountType accountType;

    private String currency = "USD";

    @Positive
    private BigDecimal initialDeposit = BigDecimal.ZERO;

    @Positive
    private BigDecimal overdraftLimit = BigDecimal.ZERO;
}
