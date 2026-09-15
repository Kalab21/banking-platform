package com.bankingplatform.user.dto;

import com.bankingplatform.user.model.DocumentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewDocumentRequest {

    @NotNull
    private DocumentStatus status;

    private String rejectionReason;

    @NotNull
    private Long reviewedBy;
}
