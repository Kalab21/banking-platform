package com.bankingplatform.creditcard.dto.response;

import com.bankingplatform.creditcard.model.CreditCardTransactionType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreditCardTransactionResponse {
    private Long id;
    private Long creditCardId;
    private String transactionRef;
    private CreditCardTransactionType type;
    private BigDecimal amount;
    private String description;
    private String merchantName;
    private String merchantCategory;
    private String status;
    private LocalDateTime createdAt;
}
