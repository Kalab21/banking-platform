package com.bankingplatform.payment.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class PaymentResponse {
    private Long id;
    private String paymentRef;
    private Long payerAccountId;
    private Long beneficiaryId;
    private Long payeeAccountId;
    private String payeeExternalRef;
    private String paymentType;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String description;
    private boolean recurring;
    private String recurrencePattern;
    private LocalDate nextExecutionDate;
    private LocalDate endDate;
    private LocalDateTime scheduledAt;
    private LocalDateTime processedAt;
    private String failureReason;
    private LocalDateTime createdAt;
}
