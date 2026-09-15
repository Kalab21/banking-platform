package com.bankingplatform.transaction.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BalanceUpdateRequest {
    private BigDecimal amount;
    private String operation;
}
