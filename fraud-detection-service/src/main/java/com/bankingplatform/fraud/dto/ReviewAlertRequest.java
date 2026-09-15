package com.bankingplatform.fraud.dto;

import com.bankingplatform.fraud.model.AlertStatus;
import lombok.Data;

@Data
public class ReviewAlertRequest {
    private AlertStatus status;
    private Long reviewedBy;
    private String resolutionNote;
}
