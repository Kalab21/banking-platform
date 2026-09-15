package com.bankingplatform.payment.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BeneficiaryResponse {
    private Long id;
    private Long userId;
    private String name;
    private String nickname;
    private String accountNumber;
    private String bankName;
    private String routingNumber;
    private String swiftCode;
    private String iban;
    private String beneficiaryType;
    private String currency;
    private boolean verified;
    private LocalDateTime createdAt;
}
