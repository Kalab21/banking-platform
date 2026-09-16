package com.bankingplatform.creditcard.dto.response;

import com.bankingplatform.creditcard.model.CardStatus;
import com.bankingplatform.creditcard.model.CardType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Browser-facing view of a credit card.
 *
 * <p>The full card number is deliberately absent. A client needs to recognise a
 * card, not to transact with the number, so only a display mask and the last
 * four digits cross the API boundary. The full PAN stays in the database and in
 * the {@code CreditCard} entity, where the service reads it for uniqueness
 * checks.
 */
@Data
public class CreditCardResponse {
    private Long id;

    /** Display form, e.g. {@code •••• •••• •••• 1234}. Never the full number. */
    private String maskedCardNumber;

    /** Last four digits, for clients that need to compose their own label. */
    private String last4;

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
