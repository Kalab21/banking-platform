package com.bankingplatform.application.dto;

import com.bankingplatform.application.model.ApplicationType;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateApplicationRequest {

    @NotNull(message = "userId is required")
    private Long userId;

    @NotNull(message = "applicationType is required")
    private ApplicationType applicationType;

    @DecimalMin(value = "0.01", message = "requestedAmount must be positive")
    private BigDecimal requestedAmount;

    private String currency;

    @Min(value = 1, message = "termMonths must be at least 1")
    @Max(value = 360, message = "termMonths cannot exceed 360")
    private Integer termMonths;

    @Size(max = 500, message = "purpose cannot exceed 500 characters")
    private String purpose;

    // What the applicant states about themselves. Which of these are required
    // depends on the product, so the per-product rules live in
    // ApplicationRequestValidator rather than in annotations here.
    //
    // There is deliberately no field for a credit score, an approved amount, an
    // APR, a term Northbank has set, a credit limit or a card tier. Those are
    // Northbank's answers, and a request object that could carry them is a
    // request object a client could use to set them.

    @DecimalMin(value = "0.01", message = "annualIncome must be positive")
    private BigDecimal annualIncome;

    @DecimalMin(value = "0.00", message = "monthlyDebtObligations cannot be negative")
    private BigDecimal monthlyDebtObligations;

    /** The vehicle's or property's value, for lending secured against it. */
    @DecimalMin(value = "0.01", message = "assetValue must be positive")
    private BigDecimal assetValue;

    @DecimalMin(value = "0.00", message = "downPayment cannot be negative")
    private BigDecimal downPayment;
}
