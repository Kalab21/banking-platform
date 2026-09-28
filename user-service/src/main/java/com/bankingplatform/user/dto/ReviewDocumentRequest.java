package com.bankingplatform.user.dto;

import com.bankingplatform.user.model.DocumentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewDocumentRequest {

    @NotNull
    private DocumentStatus status;

    private String rejectionReason;

    /**
     * Ignored if sent. The reviewer is the authenticated member of staff, so a
     * review cannot be recorded against someone else's name.
     */
    private Long reviewedBy;
}
