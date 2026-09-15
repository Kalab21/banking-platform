package com.bankingplatform.creditcard.dto.request;

import com.bankingplatform.creditcard.model.CardType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateCreditCardRequest {

    @NotNull
    private Long userId;

    private Long applicationId;

    @NotNull
    private CardType cardType;

    @NotNull
    @DecimalMin("500.00")
    private BigDecimal creditLimit;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal apr;

    private Long linkedAccountId;
}
