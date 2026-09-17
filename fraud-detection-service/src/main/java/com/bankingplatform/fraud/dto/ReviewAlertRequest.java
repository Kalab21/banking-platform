package com.bankingplatform.fraud.dto;

import com.bankingplatform.fraud.model.AlertStatus;
import lombok.Data;

/**
 * A staff decision on a fraud alert.
 *
 * <p>Carries no reviewer field. It used to, and the controller stored whatever
 * the caller put there, so the recorded reviewer was an assertion by the person
 * making the request rather than a fact. The reviewer is now taken from the
 * identity the gateway established.
 */
@Data
public class ReviewAlertRequest {
    private AlertStatus status;
    private String resolutionNote;
}
