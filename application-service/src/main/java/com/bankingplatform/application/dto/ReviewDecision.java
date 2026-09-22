package com.bankingplatform.application.dto;

/**
 * What a reviewer is allowed to decide.
 *
 * <p>Review used to take an {@link com.bankingplatform.application.model.ApplicationStatus}
 * straight from the request body and assign it, so a reviewer could put an
 * application into {@code PROVISIONED} — claiming a product existed — or back
 * into {@code SUBMITTED}, with no product and no decision behind either.
 *
 * <p>A reviewer decides; the lifecycle works out which state that implies.
 */
public enum ReviewDecision {

    /** The application meets policy in the reviewer's judgement. */
    APPROVE,

    /** It does not, and no offer will be made. */
    REJECT,

    /** The reviewer wants it looked at again rather than deciding now. */
    REFER
}
