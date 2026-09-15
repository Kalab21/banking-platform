package com.bankingplatform.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TwoFactorVerifyRequest {

    @NotBlank
    @Size(min = 6, max = 6)
    private String code;
}
