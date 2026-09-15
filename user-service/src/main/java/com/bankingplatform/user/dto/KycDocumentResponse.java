package com.bankingplatform.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KycDocumentResponse {

    private Long id;
    private Long userId;
    private String documentType;
    private String documentRef;
    private String status;
    private String rejectionReason;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
