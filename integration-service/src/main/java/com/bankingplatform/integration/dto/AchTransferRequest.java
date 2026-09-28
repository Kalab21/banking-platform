package com.bankingplatform.integration.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AchTransferRequest {
    @NotNull
    private Long fromAccountId;
    @NotBlank
    @Size(max = 140)
    private String beneficiaryName;
    @NotBlank
    @Size(max = 17)
    private String beneficiaryAccount;
    @NotBlank
    @Pattern(regexp = "\\d{9}", message = "must be nine digits")
    private String routingNumber;
    private String bankName;
    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 15, fraction = 2, message = "must be a whole number of cents")
    private BigDecimal amount;
    @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter currency code")
    private String currency;
    @Size(max = 255)
    private String purpose;
}
