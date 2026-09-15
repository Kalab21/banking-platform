package com.bankingplatform.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateOverdraftRequest {

    @NotNull
    @PositiveOrZero
    private BigDecimal overdraftLimit;
}
