package com.bankingplatform.loan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DisburseRequest {
    @NotNull
    private Long disbursementAccountId;
}
