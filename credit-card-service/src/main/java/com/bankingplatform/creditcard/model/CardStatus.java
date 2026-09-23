package com.bankingplatform.creditcard.model;

/**
 * What state a card is in, and — by implication — who put it there.
 *
 * <p>The old vocabulary was {@code ACTIVE, FROZEN, CLOSED, DEFAULT}, and the
 * service assigned whichever one the request body named. A customer could
 * therefore mark their own card {@code DEFAULT}, or clear a default the bank had
 * applied, by sending the word. {@code FROZEN} carried no record of who froze
 * it, so there was no way to tell a customer's own precautionary freeze from a
 * block the bank had imposed — and no way to let them undo the first without
 * also letting them undo the second.
 *
 * <p>Splitting the freeze is the whole point. A customer may lift what they
 * applied; everything else is the bank's to lift.
 */
public enum CardStatus {

    ACTIVE,

    /** The customer froze it, and the customer may unfreeze it. */
    CUSTOMER_FROZEN,

    /** The bank blocked it. Only the bank lifts this. */
    SYSTEM_BLOCKED,

    /** The debt went bad. Not a state a cardholder may clear by asking. */
    DEFAULTED,

    /** Terminal. A closed card is not reopened; a new one is issued instead. */
    CLOSED;

    /** Whether a card in this state may be spent on. */
    public boolean isSpendable() {
        return this == ACTIVE;
    }

    /** Whether the customer, rather than the bank, put the card here. */
    public boolean isCustomerReversible() {
        return this == CUSTOMER_FROZEN;
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
