package com.bankingplatform.application.dto;

import com.bankingplatform.application.model.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ReviewRequest {

    @NotNull(message = "status is required")
    private ApplicationStatus status;

    private BigDecimal approvedAmount;

    private String reviewerNotes;
}
