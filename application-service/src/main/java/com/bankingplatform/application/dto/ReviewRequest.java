package com.bankingplatform.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ReviewRequest {

    @NotNull(message = "decision is required")
    private ReviewDecision decision;

    /**
     * What the reviewer is prepared to approve, where that differs from what
     * was asked for. Never larger than the request: a reviewer answers an
     * application, they do not lend more than was wanted.
     */
    private BigDecimal approvedAmount;

    @Size(max = 1000, message = "reviewerNotes cannot exceed 1000 characters")
    private String reviewerNotes;
}
