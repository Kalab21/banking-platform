package com.bankingplatform.transaction.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TransactionResponse {
    private Long id;
    private String transactionRef;
    private Long accountId;
    private String type;
    private BigDecimal amount;
    private String currency;
    private BigDecimal balanceAfter;
    private String description;
    private String relatedTransactionRef;
    private String status;
    private LocalDateTime createdAt;
}
