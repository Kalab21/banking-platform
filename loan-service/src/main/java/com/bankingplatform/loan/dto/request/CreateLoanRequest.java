package com.bankingplatform.loan.dto.request;

import com.bankingplatform.loan.model.LoanType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateLoanRequest {

    @NotNull
    private Long userId;

    private Long applicationId;

    @NotNull
    private LoanType loanType;

    @NotNull
    @DecimalMin("1000.00")
    private BigDecimal principal;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal interestRate;

    @NotNull
    @Min(1) @Max(360)
    private Integer termMonths;

    private Long disbursementAccountId;
}
