package com.bankingplatform.creditcard.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may move a card into which state.
 *
 * <p>The service took a status out of the request body and assigned it, so a
 * cardholder could mark their own card defaulted — or clear a default the bank
 * had applied, and carry on spending.
 */
@DisplayName("Card status authority")
class CardStatusTransitionsTest {

    @Nested
    @DisplayName("what a customer may do")
    class Customer {

        @Test
        @DisplayName("freeze an active card, and lift their own freeze")
        void freezeAndUnfreeze() {
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.ACTIVE, CardStatus.CUSTOMER_FROZEN)).isTrue();
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.CUSTOMER_FROZEN, CardStatus.ACTIVE)).isTrue();
        }

        @Test
        @DisplayName("not clear a block the bank applied")
        void cannotClearSystemBlock() {
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.SYSTEM_BLOCKED, CardStatus.ACTIVE)).isFalse();
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.SYSTEM_BLOCKED, CardStatus.CUSTOMER_FROZEN)).isFalse();
        }

        @Test
        @DisplayName("not clear a default")
        void cannotClearDefault() {
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.DEFAULTED, CardStatus.ACTIVE)).isFalse();
        }

        @Test
        @DisplayName("not reopen a closed card")
        void cannotReopenClosed() {
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.CLOSED, CardStatus.ACTIVE)).isFalse();
        }

        @Test
        @DisplayName("not declare their own card defaulted or blocked")
        void cannotInventBankStates() {
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.ACTIVE, CardStatus.DEFAULTED)).isFalse();
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.ACTIVE, CardStatus.SYSTEM_BLOCKED)).isFalse();
            assertThat(CardStatusTransitions.customerMayMove(
                    CardStatus.ACTIVE, CardStatus.CLOSED)).isFalse();
        }
    }

    @Nested
    @DisplayName("what the bank may do")
    class Bank {

        @Test
        @DisplayName("block, default and close an active card")
        void bankHasFullAuthorityOverActive() {
            assertThat(CardStatusTransitions.bankMayMove(
                    CardStatus.ACTIVE, CardStatus.SYSTEM_BLOCKED)).isTrue();
            assertThat(CardStatusTransitions.bankMayMove(
                    CardStatus.ACTIVE, CardStatus.DEFAULTED)).isTrue();
            assertThat(CardStatusTransitions.bankMayMove(
                    CardStatus.ACTIVE, CardStatus.CLOSED)).isTrue();
        }

        @Test
        @DisplayName("lift its own block")
        void bankLiftsItsOwnBlock() {
            assertThat(CardStatusTransitions.bankMayMove(
                    CardStatus.SYSTEM_BLOCKED, CardStatus.ACTIVE)).isTrue();
        }

        @Test
        @DisplayName("not reopen a closed card either")
        void closedIsTerminalForEveryone() {
            for (CardStatus to : CardStatus.values()) {
                assertThat(CardStatusTransitions.bankMayMove(CardStatus.CLOSED, to))
                        .as("CLOSED -> %s", to)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("not quietly un-default a card back to active")
        void defaultIsNotSimplyUndone() {
            // A default is settled or the card is closed. Flipping it straight
            // back to active would erase the fact that it happened.
            assertThat(CardStatusTransitions.bankMayMove(
                    CardStatus.DEFAULTED, CardStatus.ACTIVE)).isFalse();
        }
    }

    @Nested
    @DisplayName("what the states themselves mean")
    class Meaning {

        @Test
        @DisplayName("only an active card may be spent on")
        void onlyActiveIsSpendable() {
            assertThat(CardStatus.ACTIVE.isSpendable()).isTrue();
            assertThat(CardStatus.CUSTOMER_FROZEN.isSpendable()).isFalse();
            assertThat(CardStatus.SYSTEM_BLOCKED.isSpendable()).isFalse();
            assertThat(CardStatus.DEFAULTED.isSpendable()).isFalse();
            assertThat(CardStatus.CLOSED.isSpendable()).isFalse();
        }

        @Test
        @DisplayName("only the customer's own freeze is the customer's to reverse")
        void onlyCustomerFreezeIsReversible() {
            assertThat(CardStatus.CUSTOMER_FROZEN.isCustomerReversible()).isTrue();
            assertThat(CardStatus.SYSTEM_BLOCKED.isCustomerReversible()).isFalse();
            assertThat(CardStatus.DEFAULTED.isCustomerReversible()).isFalse();
        }
    }
}
