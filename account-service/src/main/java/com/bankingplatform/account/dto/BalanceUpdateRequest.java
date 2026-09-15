package com.bankingplatform.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BalanceUpdateRequest {

    @NotNull
    @Positive
    private BigDecimal amount;

    @NotNull
    private String operation; // CREDIT or DEBIT

    private String description;
}
