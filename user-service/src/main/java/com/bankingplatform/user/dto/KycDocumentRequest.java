package com.bankingplatform.user.dto;

import com.bankingplatform.user.model.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KycDocumentRequest {

    @NotNull
    private DocumentType documentType;

    @NotBlank
    private String documentRef;
}
