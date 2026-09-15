package com.bankingplatform.loan.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanRepaymentRequest {

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;

    private Long sourceAccountId;
}
