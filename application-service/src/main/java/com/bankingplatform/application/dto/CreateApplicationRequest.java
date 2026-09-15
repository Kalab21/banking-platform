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
}
