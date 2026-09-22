package com.bankingplatform.application.model;

import com.bankingplatform.application.exception.IllegalApplicationTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The legal moves between application states, in one place.
 *
 * <p>Status used to be assigned wherever it was convenient — including
 * straight from a staff request body, which let a reviewer put an application
 * into any state at all, including one it could never have reached by working.
 * Every status write now goes through {@link #assertCanMove} so an illegal
 * move is a refusal rather than a stored contradiction.
 *
 * <p>Deposit and credit applications do not share a path. A credit product has
 * terms, so it must be offered and accepted before anything is created:
 * {@code OFFERED → ACCEPTED → PROVISIONING}. A checking or savings account has
 * no terms to offer and nothing to accept — there is no rate, no limit and no
 * amount — so it moves from review straight to provisioning. Both rules live
 * here rather than being implied by the order of calls in a service method.
 */
public final class ApplicationTransitions {

    /** Products whose approval produces terms the customer has to answer. */
    private static final Set<ApplicationType> CREDIT_PRODUCTS = EnumSet.of(
            ApplicationType.CREDIT_CARD,
            ApplicationType.PERSONAL_LOAN,
            ApplicationType.AUTO_LOAN,
            ApplicationType.MORTGAGE);

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED =
            new EnumMap<>(ApplicationStatus.class);

    static {
        ALLOWED.put(ApplicationStatus.SUBMITTED, EnumSet.of(
                ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.CANCELLED));

        ALLOWED.put(ApplicationStatus.UNDER_REVIEW, EnumSet.of(
                ApplicationStatus.OFFERED,
                ApplicationStatus.MANUAL_REVIEW,
                ApplicationStatus.REJECTED,
                // Deposit accounts only; enforced below.
                ApplicationStatus.PROVISIONING,
                ApplicationStatus.CANCELLED));

        ALLOWED.put(ApplicationStatus.MANUAL_REVIEW, EnumSet.of(
                ApplicationStatus.OFFERED,
                ApplicationStatus.REJECTED,
                ApplicationStatus.PROVISIONING,
                ApplicationStatus.CANCELLED));

        ALLOWED.put(ApplicationStatus.OFFERED, EnumSet.of(
                ApplicationStatus.ACCEPTED,
                ApplicationStatus.DECLINED,
                ApplicationStatus.CANCELLED));

        ALLOWED.put(ApplicationStatus.ACCEPTED, EnumSet.of(
                ApplicationStatus.PROVISIONING));

        ALLOWED.put(ApplicationStatus.PROVISIONING, EnumSet.of(
                ApplicationStatus.PROVISIONED));

        // Terminal states deliberately have no entry: a missing key means
        // nothing is legal, which is stronger than an empty set that someone
        // might later "fix" by adding to it.
        ALLOWED.put(ApplicationStatus.REJECTED, EnumSet.noneOf(ApplicationStatus.class));
        ALLOWED.put(ApplicationStatus.DECLINED, EnumSet.noneOf(ApplicationStatus.class));
        ALLOWED.put(ApplicationStatus.PROVISIONED, EnumSet.noneOf(ApplicationStatus.class));
        ALLOWED.put(ApplicationStatus.CANCELLED, EnumSet.noneOf(ApplicationStatus.class));
    }

    private ApplicationTransitions() {
    }

    public static boolean isCreditProduct(ApplicationType type) {
        return CREDIT_PRODUCTS.contains(type);
    }

    /** True when this move is legal for this kind of application. */
    public static boolean canMove(ApplicationType type,
                                  ApplicationStatus from,
                                  ApplicationStatus to) {
        if (from == null || to == null || from == to) {
            // Re-asserting the current state is not a transition. Treating it
            // as legal is how a double acceptance goes unnoticed.
            return false;
        }
        if (!ALLOWED.getOrDefault(from, EnumSet.noneOf(ApplicationStatus.class)).contains(to)) {
            return false;
        }
        if (to == ApplicationStatus.PROVISIONING && isCreditProduct(type)) {
            // A card or loan reaches provisioning only through an accepted
            // offer. Approval on its own does not create anything.
            return from == ApplicationStatus.ACCEPTED;
        }
        if (to == ApplicationStatus.OFFERED && !isCreditProduct(type)) {
            // There are no terms to offer on a deposit account.
            return false;
        }
        return true;
    }

    /**
     * Permits the move or refuses it, naming both states.
     *
     * @throws IllegalApplicationTransitionException if the move is not legal
     */
    public static void assertCanMove(ApplicationType type,
                                     ApplicationStatus from,
                                     ApplicationStatus to) {
        if (!canMove(type, from, to)) {
            throw new IllegalApplicationTransitionException(type, from, to);
        }
    }
}
