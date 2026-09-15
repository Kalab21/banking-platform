package com.bankingplatform.creditcard.dto.request;

import com.bankingplatform.creditcard.model.CardStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCardStatusRequest {
    @NotNull
    private CardStatus status;
}
