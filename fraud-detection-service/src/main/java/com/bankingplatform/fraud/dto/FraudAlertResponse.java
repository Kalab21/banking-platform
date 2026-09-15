package com.bankingplatform.fraud.dto;

import com.bankingplatform.fraud.model.AlertStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder
public class FraudAlertResponse {
    private Long id;
    private Long accountId;
    private Long userId;
    private String alertType;
    private int riskScore;
    private String description;
    private String eventRef;
    private String eventType;
    private BigDecimal amount;
    private AlertStatus status;
    private LocalDateTime createdAt;
}
