package com.bankingplatform.account.dto;

import com.bankingplatform.account.model.AccountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateAccountRequest {

    @NotNull
    private Long userId;

    @NotNull
    private AccountType accountType;

    private String currency = "USD";

    /**
     * A lending decision, so staff only. Left null, the bank's default for the
     * account type applies; a customer who states one is refused.
     */
    @PositiveOrZero
    private BigDecimal overdraftLimit;
}
