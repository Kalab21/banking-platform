package com.bankingplatform.application.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ApplicationResponse {
    private Long id;
    private Long userId;
    private String applicationType;
    private String status;
    private BigDecimal requestedAmount;
    private BigDecimal approvedAmount;
    private String currency;
    private Integer termMonths;
    private String purpose;
    private Integer creditScoreAtApply;

    // What the applicant stated. Returned so a customer can see the
    // application they submitted, and so staff review reads the figures the
    // decision was actually made against.
    private BigDecimal annualIncome;
    private BigDecimal monthlyDebtObligations;
    private BigDecimal assetValue;
    private BigDecimal downPayment;
    private String reviewerNotes;
    private Long productId;
    private LocalDateTime appliedAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
