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

    /** Zero is valid: an account may be opened empty and funded later. */
    @PositiveOrZero
    private BigDecimal initialDeposit = BigDecimal.ZERO;

    /** Zero is valid and is the norm for savings accounts, which have no overdraft. */
    @PositiveOrZero
    private BigDecimal overdraftLimit = BigDecimal.ZERO;
}
