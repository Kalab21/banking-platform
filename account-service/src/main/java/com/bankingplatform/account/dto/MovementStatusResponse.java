package com.bankingplatform.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Whether a balance movement with a given key was applied.
 *
 * <p>Exists so a caller that lost the answer can ask for it rather than
 * guess. A transfer whose debit timed out has no way to know whether the
 * money left; the idempotency record here does, because it is written by the
 * same transaction that moved it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovementStatusResponse {

    /** The key the caller asked about. */
    private String idempotencyKey;

    /** True only when the movement completed and the balance changed. */
    private boolean applied;

    /**
     * IN_PROGRESS, COMPLETED, FAILED, UNKNOWN, or NOT_FOUND when this service
     * has never seen the key -- which means the request never arrived, and the
     * caller may send it.
     */
    private String status;
}
