package com.bankingplatform.application.underwriting;

import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo underwriting policy, tested as arithmetic.
 *
 * <p>Every case here states the figures and the expected answer, because the
 * point of a deterministic policy is that the answer is derivable by hand. If
 * one of these has to be run to find out what it does, the policy is not doing
 * its job.
 */
@DisplayName("Underwriting policy")
class UnderwritingServiceTest {

    private final UnderwritingService underwriting = new UnderwritingService(new UnderwritingPolicy());

    /** A personal loan well inside every limit, which individual tests then spoil. */
    private static Application.ApplicationBuilder soundPersonalLoan() {
        return Application.builder()
                .applicationType(ApplicationType.PERSONAL_LOAN)
                .requestedAmount(new BigDecimal("10000.00"))
                .termMonths(48)
                .annualIncome(new BigDecimal("90000.00"))
                .monthlyDebtObligations(new BigDecimal("1500.00"));
    }

    @Nested
    @DisplayName("the ratios")
    class Ratios {

        @Test
        @DisplayName("DTI is monthly debt over gross monthly income")
        void dtiIsDebtOverGrossMonthlyIncome() {
            // 90,000 / 12 = 7,500 gross monthly. 1,500 / 7,500 = 0.2000.
            UnderwritingDecision decision = underwriting.decide(
                    soundPersonalLoan().build(), 780, "VERIFIED");

            assertThat(decision.dti()).isEqualByComparingTo(new BigDecimal("0.2000"));
        }

        @Test
        @DisplayName("LTV is what is borrowed over what secures it, net of the deposit")
        void ltvIsNetOfDownPayment() {
            // 30,000 asked, 6,000 down, 24,000 borrowed against a 25,000 car.
            // 24,000 / 25,000 = 0.9600.
            Application auto = Application.builder()
                    .applicationType(ApplicationType.AUTO_LOAN)
                    .requestedAmount(new BigDecimal("30000.00"))
                    .downPayment(new BigDecimal("6000.00"))
                    .assetValue(new BigDecimal("25000.00"))
                    .termMonths(60)
                    .annualIncome(new BigDecimal("120000.00"))
                    .monthlyDebtObligations(new BigDecimal("1000.00"))
                    .build();

            UnderwritingDecision decision = underwriting.decide(auto, 780, "VERIFIED");

            assertThat(decision.ltv()).isEqualByComparingTo(new BigDecimal("0.9600"));
            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.APPROVE);
        }

        @Test
        @DisplayName("income of zero is no ratio rather than an infinite one")
        void zeroIncomeIsMissingNotFailing() {
            // Dividing by it would throw, and calling it an enormous DTI would
            // refuse the customer for a gap in the form.
            Application application = soundPersonalLoan()
                    .annualIncome(BigDecimal.ZERO)
                    .build();

            UnderwritingDecision decision = underwriting.decide(application, 780, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REFER);
            assertThat(decision.reasonCodes()).contains(ReasonCode.INSUFFICIENT_INFORMATION);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a score below the product minimum is refused, naming the score")
        void scoreBelowMinimum() {
            UnderwritingDecision decision = underwriting.decide(
                    soundPersonalLoan().build(), 550, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REJECT);
            assertThat(decision.reasonCodes()).containsExactly(ReasonCode.CREDIT_SCORE_BELOW_MINIMUM);
            assertThat(decision.approvedAmount()).isNull();
        }

        @Test
        @DisplayName("DTI above the maximum is refused and the ratio is carried")
        void dtiAboveMaximum() {
            // 3,500 / 7,500 = 0.4667, above the personal-loan maximum of 0.43.
            Application application = soundPersonalLoan()
                    .monthlyDebtObligations(new BigDecimal("3500.00"))
                    .build();

            UnderwritingDecision decision = underwriting.decide(application, 780, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REJECT);
            assertThat(decision.reasonCodes()).contains(ReasonCode.DTI_ABOVE_POLICY);
            assertThat(decision.dti()).isEqualByComparingTo(new BigDecimal("0.4667"));
        }

        @Test
        @DisplayName("more than the product lends is refused")
        void aboveMaxAmount() {
            Application application = soundPersonalLoan()
                    .requestedAmount(new BigDecimal("75000.00"))
                    .build();

            UnderwritingDecision decision = underwriting.decide(application, 780, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REJECT);
            assertThat(decision.reasonCodes()).contains(ReasonCode.REQUEST_AMOUNT_ABOVE_POLICY);
        }

        @Test
        @DisplayName("a term the product does not offer is refused")
        void unsupportedTerm() {
            Application application = soundPersonalLoan().termMonths(7).build();

            UnderwritingDecision decision = underwriting.decide(application, 780, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REJECT);
            assertThat(decision.reasonCodes()).contains(ReasonCode.TERM_NOT_SUPPORTED);
        }

        @Test
        @DisplayName("LTV above the maximum is refused")
        void ltvAboveMaximum() {
            Application mortgage = Application.builder()
                    .applicationType(ApplicationType.MORTGAGE)
                    .requestedAmount(new BigDecimal("400000.00"))
                    .assetValue(new BigDecimal("400000.00"))
                    .termMonths(360)
                    .annualIncome(new BigDecimal("250000.00"))
                    .monthlyDebtObligations(new BigDecimal("2000.00"))
                    .build();

            UnderwritingDecision decision = underwriting.decide(mortgage, 780, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REJECT);
            assertThat(decision.reasonCodes()).contains(ReasonCode.LTV_ABOVE_POLICY);
            assertThat(decision.ltv()).isEqualByComparingTo(new BigDecimal("1.0000"));
        }

        @Test
        @DisplayName("a refusal never carries an approved amount")
        void refusalApprovesNothing() {
            UnderwritingDecision decision = underwriting.decide(
                    soundPersonalLoan().build(), 400, "VERIFIED");

            assertThat(decision.isRejected()).isTrue();
            assertThat(decision.approvedAmount()).isNull();
        }
    }

    @Nested
    @DisplayName("KYC is not a binary of rejected and fine")
    class Kyc {

        /** The policy as it will be once staff can approve KYC. */
        private UnderwritingService withKycEnforced() {
            UnderwritingPolicy armed = new UnderwritingPolicy();
            armed.getProducts().values().forEach(rules -> rules.setRequiresVerifiedKyc(true));
            return new UnderwritingService(armed);
        }

        @Test
        @DisplayName("a failed check refuses even though the gate is off")
        void rejectedKycRefusesRegardlessOfTheGate() {
            // The gate governs unfinished checks. A check that was failed is a
            // refusal whatever the gate says, and wiring it otherwise would
            // have made this weaker than the code it replaced.
            UnderwritingDecision decision = underwriting.decide(
                    soundPersonalLoan().build(), 780, "REJECTED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REJECT);
            assertThat(decision.reasonCodes()).containsExactly(ReasonCode.KYC_REJECTED);
        }

        @Test
        @DisplayName("a failed check refuses a deposit account too")
        void rejectedKycRefusesDepositAccounts() {
            Application application = Application.builder()
                    .applicationType(ApplicationType.CHECKING_ACCOUNT)
                    .build();

            assertThat(underwriting.decide(application, 800, "REJECTED").isRejected()).isTrue();
        }

        @Test
        @DisplayName("with the gate off, an unfinished check does not hold an application up")
        void pendingKycPassesWhileTheGateIsOff() {
            // Today a customer registers PENDING and reaches IN_REVIEW at best,
            // so arming this would refer every application ever submitted.
            UnderwritingDecision decision = underwriting.decide(
                    soundPersonalLoan().build(), 780, "PENDING");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.APPROVE);
        }

        @Test
        @DisplayName("with the gate on, an unfinished check refers rather than approving")
        void pendingKycRefersWhenEnforced() {
            // The bug this exists for: treating "not REJECTED" as "VERIFIED"
            // lends to someone whose identity was never established.
            UnderwritingDecision decision = withKycEnforced().decide(
                    soundPersonalLoan().build(), 780, "PENDING");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REFER);
            assertThat(decision.reasonCodes()).contains(ReasonCode.KYC_REVIEW_REQUIRED);
        }

        @Test
        @DisplayName("with the gate on, in review is unfinished and an absent status is too")
        void inReviewAndMissingReferWhenEnforced() {
            UnderwritingService armed = withKycEnforced();

            assertThat(armed.decide(soundPersonalLoan().build(), 780, "IN_REVIEW").isReferred()).isTrue();
            assertThat(armed.decide(soundPersonalLoan().build(), 780, null).isReferred()).isTrue();
        }

        @Test
        @DisplayName("the state user-service calls APPROVED is the cleared one")
        void approvedIsTheClearedState() {
            // user-service's enum is PENDING, IN_REVIEW, APPROVED, REJECTED.
            // Matching on a name it never emits would refer every customer.
            UnderwritingService armed = withKycEnforced();

            assertThat(armed.decide(soundPersonalLoan().build(), 780, "APPROVED").isApproved()).isTrue();
            assertThat(armed.decide(soundPersonalLoan().build(), 780, "VERIFIED").isApproved()).isTrue();
        }
    }

    @Nested
    @DisplayName("referrals")
    class Referrals {

        @Test
        @DisplayName("a score inside the minimum but under the comfortable line refers")
        void borderlineScoreRefers() {
            // 620 clears the personal-loan minimum of 600 and is under 640.
            UnderwritingDecision decision = underwriting.decide(
                    soundPersonalLoan().build(), 620, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REFER);
            assertThat(decision.reasonCodes()).contains(ReasonCode.CREDIT_SCORE_BELOW_MINIMUM);
            // A referral still carries what was asked for: a reviewer needs it.
            assertThat(decision.approvedAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
        }

        @Test
        @DisplayName("a DTI inside the maximum but above the comfortable line refers")
        void borderlineDtiRefers() {
            // 3,000 / 7,500 = 0.4000: under the 0.43 maximum, over the 0.38 line.
            Application application = soundPersonalLoan()
                    .monthlyDebtObligations(new BigDecimal("3000.00"))
                    .build();

            UnderwritingDecision decision = underwriting.decide(application, 780, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REFER);
            assertThat(decision.reasonCodes()).contains(ReasonCode.DTI_ABOVE_POLICY);
        }

        @Test
        @DisplayName("a missing figure the policy needs refers rather than refusing")
        void missingIncomeRefers() {
            Application application = soundPersonalLoan().annualIncome(null).build();

            UnderwritingDecision decision = underwriting.decide(application, 780, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.REFER);
            assertThat(decision.reasonCodes()).contains(ReasonCode.INSUFFICIENT_INFORMATION);
        }

        @Test
        @DisplayName("a referral is never empty of reasons")
        void referralAlwaysStatesAReason() {
            UnderwritingDecision decision = underwriting.decide(
                    soundPersonalLoan().build(), 620, "VERIFIED");

            assertThat(decision.reasonCodes()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("deposit accounts are not lending")
    class DepositAccounts {

        @Test
        @DisplayName("a verified customer opening a checking account is approved on no ratios")
        void checkingIsApproved() {
            Application application = Application.builder()
                    .applicationType(ApplicationType.CHECKING_ACCOUNT)
                    .build();

            UnderwritingDecision decision = underwriting.decide(application, 0, "VERIFIED");

            assertThat(decision.outcome()).isEqualTo(UnderwritingDecision.Outcome.APPROVE);
            assertThat(decision.dti()).isNull();
            assertThat(decision.ltv()).isNull();
        }

        @Test
        @DisplayName("a credit score of zero does not stop someone opening a savings account")
        void savingsIgnoresScore() {
            Application application = Application.builder()
                    .applicationType(ApplicationType.SAVINGS_ACCOUNT)
                    .build();

            assertThat(underwriting.decide(application, 0, "VERIFIED").isApproved()).isTrue();
        }

        @Test
        @DisplayName("but identity still has to be established, once the gate is armed")
        void depositStillNeedsKyc() {
            UnderwritingPolicy armed = new UnderwritingPolicy();
            armed.getProducts().values().forEach(rules -> rules.setRequiresVerifiedKyc(true));
            Application application = Application.builder()
                    .applicationType(ApplicationType.CHECKING_ACCOUNT)
                    .build();

            assertThat(new UnderwritingService(armed).decide(application, 800, "PENDING").isReferred()).isTrue();
        }
    }

    @Nested
    @DisplayName("the decision is reproducible")
    class Reproducible {

        @Test
        @DisplayName("the same application decides the same way every time")
        void deterministic() {
            Application application = soundPersonalLoan().build();

            UnderwritingDecision first = underwriting.decide(application, 700, "VERIFIED");
            UnderwritingDecision second = underwriting.decide(application, 700, "VERIFIED");

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("every decision names the policy it was taken under")
        void stampsThePolicyVersion() {
            UnderwritingDecision decision = underwriting.decide(
                    soundPersonalLoan().build(), 780, "VERIFIED");

            assertThat(decision.policyVersion()).isEqualTo(underwriting.policyVersion());
            assertThat(decision.policyVersion()).isNotBlank();
        }
    }
}
