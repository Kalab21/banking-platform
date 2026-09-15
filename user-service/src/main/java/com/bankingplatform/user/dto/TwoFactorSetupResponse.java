package com.bankingplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TwoFactorSetupResponse {

    private String secret;
    private String otpauthUri;
    private String message;
}
