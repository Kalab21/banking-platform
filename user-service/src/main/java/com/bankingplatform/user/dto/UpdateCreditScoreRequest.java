package com.bankingplatform.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCreditScoreRequest {

    /** No change can move a score further than the whole 300-850 range. */
    @NotNull
    @Min(-550)
    @Max(550)
    private Integer delta;

    @NotBlank
    private String reason;
}
