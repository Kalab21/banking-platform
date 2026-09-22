package com.bankingplatform.application.model;

/**
 * Where an application has got to — which is not the same question as what
 * state the product is in.
 *
 * <p>The previous vocabulary was {@code PENDING, UNDER_REVIEW, APPROVED,
 * REJECTED, CANCELLED, DISBURSED}, and every approved application ended at
 * {@code DISBURSED} whatever it was for. A credit card is not disbursed, and
 * an application whose downstream product had never been confirmed said
 * {@code DISBURSED} regardless: the status described the intention rather than
 * the outcome.
 *
 * <p>These states describe the application's own progress only. The product's
 * lifecycle — a loan being funded, a card being frozen — belongs to the
 * service that owns the product.
 *
 * @see ApplicationTransitions for which moves between these are legal
 */
public enum ApplicationStatus {

    /** Received and stored; no decision has been attempted yet. */
    SUBMITTED,

    /** Being assessed against policy. */
    UNDER_REVIEW,

    /** Policy referred it to a person; awaiting a reviewer. */
    MANUAL_REVIEW,

    /** Approved, with terms the customer has not yet answered. */
    OFFERED,

    /** Refused. Terminal. */
    REJECTED,

    /** The customer took the offered terms. */
    ACCEPTED,

    /** The customer turned the offered terms down. Terminal. */
    DECLINED,

    /**
     * The product is being created downstream.
     *
     * <p>An application sits here until the service that owns the product says
     * it exists. It is not evidence that a product was created — only that one
     * was asked for.
     */
    PROVISIONING,

    /**
     * The product exists and this application holds its real id.
     *
     * <p>Reached only on downstream confirmation. Terminal.
     */
    PROVISIONED,

    /** Withdrawn by the customer before any product was created. Terminal. */
    CANCELLED;

    /** No further transition is legal from a terminal state. */
    public boolean isTerminal() {
        return this == REJECTED || this == DECLINED || this == PROVISIONED || this == CANCELLED;
    }
}
