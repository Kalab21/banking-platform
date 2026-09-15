package com.bankingplatform.creditcard.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class CreditCardStatementResponse {
    private Long id;
    private Long creditCardId;
    private LocalDate statementDate;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal totalPurchases;
    private BigDecimal totalPayments;
    private BigDecimal interestCharged;
    private BigDecimal feesCharged;
    private Integer rewardsEarned;
    private BigDecimal minimumPayment;
    private LocalDate paymentDueDate;
    private Boolean paidInFull;
    private LocalDateTime createdAt;
}
