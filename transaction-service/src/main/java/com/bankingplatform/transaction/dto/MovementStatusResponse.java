package com.bankingplatform.transaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** account-service's answer to "was this movement applied?". */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovementStatusResponse {

    private String idempotencyKey;

    /** True only when the movement completed and the balance changed. */
    private boolean applied;

    /** IN_PROGRESS, COMPLETED, FAILED, UNKNOWN, or NOT_FOUND. */
    private String status;
}
