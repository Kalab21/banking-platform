package com.bankingplatform.payment.dto;

import com.bankingplatform.payment.model.PaymentType;
import com.bankingplatform.payment.model.RecurrencePattern;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class CreatePaymentRequest {

    @NotNull(message = "payerAccountId is required")
    private Long payerAccountId;

    private Long beneficiaryId;

    private Long payeeAccountId;

    private String payeeExternalRef;

    @NotNull(message = "paymentType is required")
    private PaymentType paymentType;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    private BigDecimal amount;

    private String currency;

    private String description;

    private boolean recurring;

    private RecurrencePattern recurrencePattern;

    private LocalDate endDate;

    private LocalDateTime scheduledAt;
}
