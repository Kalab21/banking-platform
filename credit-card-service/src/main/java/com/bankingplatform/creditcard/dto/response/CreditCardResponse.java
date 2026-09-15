package com.bankingplatform.creditcard.dto.response;

import com.bankingplatform.creditcard.model.CardStatus;
import com.bankingplatform.creditcard.model.CardType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class CreditCardResponse {
    private Long id;
    private String cardNumber;
    private Long userId;
    private Long applicationId;
    private CardType cardType;
    private BigDecimal creditLimit;
    private BigDecimal availableCredit;
    private BigDecimal currentBalance;
    private BigDecimal statementBalance;
    private BigDecimal minimumPaymentDue;
    private LocalDate paymentDueDate;
    private BigDecimal apr;
    private Integer billingCycleDay;
    private CardStatus status;
    private String currency;
    private Integer rewardsPoints;
    private Long linkedAccountId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
