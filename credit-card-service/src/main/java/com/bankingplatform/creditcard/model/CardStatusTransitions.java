package com.bankingplatform.creditcard.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Who may move a card into which state.
 *
 * <p>The service used to take a {@link CardStatus} out of the request body and
 * assign it. That is the same defect the application lifecycle had: a field a
 * caller controls, written straight onto a record that decides what the bank
 * will honour. A customer could mark their own card {@code DEFAULTED}, or —
 * worse — clear one the bank had applied, and spend again.
 *
 * <p>Two tables rather than one, because authority is the point. A customer may
 * take precautions and undo them. A default, a block and a closure are the
 * bank's, and stay the bank's.
 */
public final class CardStatusTransitions {

    /** What a cardholder may do to their own card. */
    private static final Map<CardStatus, Set<CardStatus>> CUSTOMER =
            new EnumMap<>(CardStatus.class);

    /** What staff and the platform's own processes may do. */
    private static final Map<CardStatus, Set<CardStatus>> BANK =
            new EnumMap<>(CardStatus.class);

    static {
        // A customer may freeze an active card and lift their own freeze. That
        // is the whole of their authority over status.
        CUSTOMER.put(CardStatus.ACTIVE, EnumSet.of(CardStatus.CUSTOMER_FROZEN));
        CUSTOMER.put(CardStatus.CUSTOMER_FROZEN, EnumSet.of(CardStatus.ACTIVE));
        CUSTOMER.put(CardStatus.SYSTEM_BLOCKED, EnumSet.noneOf(CardStatus.class));
        CUSTOMER.put(CardStatus.DEFAULTED, EnumSet.noneOf(CardStatus.class));
        CUSTOMER.put(CardStatus.CLOSED, EnumSet.noneOf(CardStatus.class));

        BANK.put(CardStatus.ACTIVE, EnumSet.of(
                CardStatus.CUSTOMER_FROZEN, CardStatus.SYSTEM_BLOCKED,
                CardStatus.DEFAULTED, CardStatus.CLOSED));
        BANK.put(CardStatus.CUSTOMER_FROZEN, EnumSet.of(
                CardStatus.ACTIVE, CardStatus.SYSTEM_BLOCKED,
                CardStatus.DEFAULTED, CardStatus.CLOSED));
        BANK.put(CardStatus.SYSTEM_BLOCKED, EnumSet.of(
                CardStatus.ACTIVE, CardStatus.DEFAULTED, CardStatus.CLOSED));
        BANK.put(CardStatus.DEFAULTED, EnumSet.of(
                CardStatus.SYSTEM_BLOCKED, CardStatus.CLOSED));
        // Terminal for everyone. A closed card is replaced, not revived.
        BANK.put(CardStatus.CLOSED, EnumSet.noneOf(CardStatus.class));
    }

    private CardStatusTransitions() {
    }

    public static boolean customerMayMove(CardStatus from, CardStatus to) {
        return CUSTOMER.getOrDefault(from, EnumSet.noneOf(CardStatus.class)).contains(to);
    }

    public static boolean bankMayMove(CardStatus from, CardStatus to) {
        return BANK.getOrDefault(from, EnumSet.noneOf(CardStatus.class)).contains(to);
    }

    public static boolean mayMove(CardStatus from, CardStatus to, boolean actingAsStaff) {
        return actingAsStaff ? bankMayMove(from, to) : customerMayMove(from, to);
    }
}
