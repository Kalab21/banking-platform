package com.bankingplatform.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCreditScoreRequest {

    @NotNull
    private Integer delta;

    @NotBlank
    private String reason;
}
