package com.bankingplatform.payment.dto;

import com.bankingplatform.payment.model.BeneficiaryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateBeneficiaryRequest {

    @NotNull(message = "userId is required")
    private Long userId;

    @NotBlank(message = "name is required")
    private String name;

    private String nickname;

    private String accountNumber;

    private String bankName;

    private String routingNumber;

    private String swiftCode;

    private String iban;

    @NotNull(message = "beneficiaryType is required")
    private BeneficiaryType beneficiaryType;

    private String currency;
}
