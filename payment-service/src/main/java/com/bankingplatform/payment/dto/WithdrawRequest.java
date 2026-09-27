package com.bankingplatform.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** The body of transaction-service's withdrawal, for money leaving the bank. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawRequest {
    private Long accountId;
    private BigDecimal amount;
    private String description;
}
