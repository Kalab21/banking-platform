package com.bankingplatform.integration.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AccountValidationResponse {
    private String accountNumber;
    private boolean valid;
    private String message;
}
