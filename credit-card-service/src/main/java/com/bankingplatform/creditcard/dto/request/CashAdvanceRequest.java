package com.bankingplatform.creditcard.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CashAdvanceRequest {

    @NotNull
    @DecimalMin("0.01")
    @jakarta.validation.constraints.Digits(integer = 15, fraction = 2,
            message = "must be a whole number of cents")
    private BigDecimal amount;

    @NotNull
    private Long targetAccountId;
}
