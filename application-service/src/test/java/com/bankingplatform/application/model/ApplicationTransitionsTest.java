package com.bankingplatform.application.model;

import com.bankingplatform.application.exception.IllegalApplicationTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The application lifecycle, stated as the moves it permits and refuses.
 *
 * <p>Status used to be assigned from whatever the caller sent, so an
 * application could be put into any state from any other. The interesting
 * cases here are the refusals: they are the ones that used to be possible.
 */
@DisplayName("ApplicationTransitions")
class ApplicationTransitionsTest {

    private static final ApplicationType CARD = ApplicationType.CREDIT_CARD;
    private static final ApplicationType LOAN = ApplicationType.PERSONAL_LOAN;
    private static final ApplicationType CHECKING = ApplicationType.CHECKING_ACCOUNT;

    private static boolean can(ApplicationType type, ApplicationStatus from, ApplicationStatus to) {
        return ApplicationTransitions.canMove(type, from, to);
    }

    @Nested
    @DisplayName("the path a credit application takes")
    class CreditPath {

        @Test
        @DisplayName("submitted, reviewed, offered, accepted, provisioning, provisioned")
        void happyPath() {
            assertThat(can(CARD, ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW)).isTrue();
            assertThat(can(CARD, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.OFFERED)).isTrue();
            assertThat(can(CARD, ApplicationStatus.OFFERED, ApplicationStatus.ACCEPTED)).isTrue();
            assertThat(can(CARD, ApplicationStatus.ACCEPTED, ApplicationStatus.PROVISIONING)).isTrue();
            assertThat(can(CARD, ApplicationStatus.PROVISIONING, ApplicationStatus.PROVISIONED)).isTrue();
        }

        @Test
        @DisplayName("review may refer to a person, and a referral may still be offered or refused")
        void referralPath() {
            assertThat(can(CARD, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.MANUAL_REVIEW)).isTrue();
            assertThat(can(CARD, ApplicationStatus.MANUAL_REVIEW, ApplicationStatus.OFFERED)).isTrue();
            assertThat(can(CARD, ApplicationStatus.MANUAL_REVIEW, ApplicationStatus.REJECTED)).isTrue();
        }

        @Test
        @DisplayName("an offer may be declined")
        void declinePath() {
            assertThat(can(CARD, ApplicationStatus.OFFERED, ApplicationStatus.DECLINED)).isTrue();
        }

        @Test
        @DisplayName("approval alone does not reach provisioning: the offer must be accepted")
        void creditCannotSkipAcceptance() {
            // This is the rule that stops an approval creating a product the
            // customer never agreed to.
            assertThat(can(CARD, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.PROVISIONING)).isFalse();
            assertThat(can(CARD, ApplicationStatus.MANUAL_REVIEW, ApplicationStatus.PROVISIONING)).isFalse();
            assertThat(can(LOAN, ApplicationStatus.OFFERED, ApplicationStatus.PROVISIONING)).isFalse();
        }
    }

    @Nested
    @DisplayName("the path a deposit account takes")
    class DepositPath {

        @Test
        @DisplayName("review goes straight to provisioning, because there are no terms to offer")
        void noOfferStage() {
            assertThat(can(CHECKING, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.PROVISIONING)).isTrue();
            assertThat(can(CHECKING, ApplicationStatus.PROVISIONING, ApplicationStatus.PROVISIONED)).isTrue();
        }

        @Test
        @DisplayName("a deposit account is never offered: there is no rate, limit or amount to offer")
        void cannotBeOffered() {
            assertThat(can(CHECKING, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.OFFERED)).isFalse();
            assertThat(can(ApplicationType.SAVINGS_ACCOUNT,
                    ApplicationStatus.UNDER_REVIEW, ApplicationStatus.OFFERED)).isFalse();
        }
    }

    @Nested
    @DisplayName("the moves that used to be possible")
    class Refusals {

        @Test
        @DisplayName("a second acceptance of the same offer is not a transition")
        void doubleAcceptance() {
            // Re-asserting the current state has to be refused rather than
            // treated as a no-op: an idempotent-looking success is how a
            // second provisioning request gets sent.
            assertThat(can(CARD, ApplicationStatus.ACCEPTED, ApplicationStatus.ACCEPTED)).isFalse();
        }

        @Test
        @DisplayName("a declined offer cannot then be accepted")
        void acceptAfterDecline() {
            assertThat(can(CARD, ApplicationStatus.DECLINED, ApplicationStatus.ACCEPTED)).isFalse();
            assertThat(can(CARD, ApplicationStatus.DECLINED, ApplicationStatus.PROVISIONING)).isFalse();
        }

        @Test
        @DisplayName("a rejected application cannot be accepted or offered")
        void acceptAfterRejection() {
            assertThat(can(CARD, ApplicationStatus.REJECTED, ApplicationStatus.ACCEPTED)).isFalse();
            assertThat(can(CARD, ApplicationStatus.REJECTED, ApplicationStatus.OFFERED)).isFalse();
            assertThat(can(CARD, ApplicationStatus.REJECTED, ApplicationStatus.UNDER_REVIEW)).isFalse();
        }

        @Test
        @DisplayName("cancelling is refused once a product has been asked for or created")
        void cancelAfterProvisioning() {
            // Withdrawing an application cannot un-issue a card.
            assertThat(can(CARD, ApplicationStatus.PROVISIONING, ApplicationStatus.CANCELLED)).isFalse();
            assertThat(can(CARD, ApplicationStatus.PROVISIONED, ApplicationStatus.CANCELLED)).isFalse();
            assertThat(can(CHECKING, ApplicationStatus.PROVISIONED, ApplicationStatus.CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("cancelling is allowed while the customer still has something to withdraw")
        void cancelBeforeProvisioning() {
            assertThat(can(CARD, ApplicationStatus.SUBMITTED, ApplicationStatus.CANCELLED)).isTrue();
            assertThat(can(CARD, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.CANCELLED)).isTrue();
            assertThat(can(CARD, ApplicationStatus.OFFERED, ApplicationStatus.CANCELLED)).isTrue();
        }

        @Test
        @DisplayName("provisioned cannot go back to anything")
        void provisionedIsFinal() {
            for (ApplicationStatus to : ApplicationStatus.values()) {
                assertThat(can(CARD, ApplicationStatus.PROVISIONED, to))
                        .as("PROVISIONED -> %s", to)
                        .isFalse();
            }
        }

        @ParameterizedTest
        @EnumSource(ApplicationStatus.class)
        @DisplayName("no state may move to SUBMITTED: an application is submitted once")
        void nothingReturnsToSubmitted(ApplicationStatus from) {
            assertThat(can(CARD, from, ApplicationStatus.SUBMITTED)).isFalse();
        }
    }

    @Nested
    @DisplayName("terminal states")
    class Terminals {

        @ParameterizedTest
        @EnumSource(value = ApplicationStatus.class,
                names = {"REJECTED", "DECLINED", "PROVISIONED", "CANCELLED"})
        @DisplayName("are terminal and permit nothing")
        void terminalPermitsNothing(ApplicationStatus terminal) {
            assertThat(terminal.isTerminal()).isTrue();
            for (ApplicationStatus to : ApplicationStatus.values()) {
                assertThat(can(CARD, terminal, to)).isFalse();
            }
        }

        @ParameterizedTest
        @EnumSource(value = ApplicationStatus.class,
                names = {"SUBMITTED", "UNDER_REVIEW", "MANUAL_REVIEW", "OFFERED",
                        "ACCEPTED", "PROVISIONING"})
        @DisplayName("everything else is still in flight")
        void inFlightIsNotTerminal(ApplicationStatus status) {
            assertThat(status.isTerminal()).isFalse();
        }
    }

    @Nested
    @DisplayName("assertCanMove")
    class Asserting {

        @Test
        @DisplayName("names both ends of a refused move")
        void messageNamesBothStates() {
            assertThatThrownBy(() -> ApplicationTransitions.assertCanMove(
                    CARD, ApplicationStatus.REJECTED, ApplicationStatus.PROVISIONED))
                    .isInstanceOf(IllegalApplicationTransitionException.class)
                    .hasMessageContaining("CREDIT_CARD")
                    .hasMessageContaining("REJECTED")
                    .hasMessageContaining("PROVISIONED");
        }

        @Test
        @DisplayName("permits a legal move silently")
        void legalMovePasses() {
            assertThatCode(() -> ApplicationTransitions.assertCanMove(
                    CARD, ApplicationStatus.OFFERED, ApplicationStatus.ACCEPTED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a null state rather than assuming one")
        void nullStates() {
            assertThat(can(CARD, null, ApplicationStatus.UNDER_REVIEW)).isFalse();
            assertThat(can(CARD, ApplicationStatus.SUBMITTED, null)).isFalse();
        }
    }

    @Test
    @DisplayName("credit products are the four that carry terms")
    void creditProducts() {
        assertThat(ApplicationTransitions.isCreditProduct(ApplicationType.CREDIT_CARD)).isTrue();
        assertThat(ApplicationTransitions.isCreditProduct(ApplicationType.PERSONAL_LOAN)).isTrue();
        assertThat(ApplicationTransitions.isCreditProduct(ApplicationType.AUTO_LOAN)).isTrue();
        assertThat(ApplicationTransitions.isCreditProduct(ApplicationType.MORTGAGE)).isTrue();
        assertThat(ApplicationTransitions.isCreditProduct(ApplicationType.CHECKING_ACCOUNT)).isFalse();
        assertThat(ApplicationTransitions.isCreditProduct(ApplicationType.SAVINGS_ACCOUNT)).isFalse();
    }
}
