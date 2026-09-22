package com.bankingplatform.application.underwriting;

import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationTransitions;
import com.bankingplatform.application.model.ApplicationType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides an application against {@link UnderwritingPolicy}.
 *
 * <p>Deterministic and arithmetic. The same application against the same policy
 * version always decides the same way: there is no model here, no randomness,
 * nothing consulted over a network, and nothing that could answer differently
 * tomorrow. That is what makes a stored decision worth keeping — it can be
 * re-derived and checked.
 *
 * <p>Three outcomes, and the middle one matters. A rule that is outside the
 * hard limit refuses. A rule that is inside the limit but close to it refers,
 * because "no" and "not without a person looking" are different answers and
 * collapsing them either lends too freely or turns away business a human would
 * have taken. Missing information also refers rather than refusing: the request
 * has not been judged and failed, it cannot be judged yet.
 *
 * <p>All money arithmetic is BigDecimal. Ratios are carried at four decimal
 * places, which is finer than any threshold compares at, so a rounding step
 * never decides a borderline case.
 */
@Service
public class UnderwritingService {

    /** Finer than any threshold, so rounding never tips a comparison. */
    private static final int RATIO_SCALE = 4;

    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    private final UnderwritingPolicy policy;

    public UnderwritingService(UnderwritingPolicy policy) {
        this.policy = policy;
    }

    /** The policy these decisions are taken under, for stamping a reviewer's. */
    public String policyVersion() {
        return policy.getVersion();
    }

    public UnderwritingDecision decide(Application application, Integer creditScore, String kycStatus) {
        ApplicationType type = application.getApplicationType();
        UnderwritingPolicy.ProductRules rules = policy.rulesFor(type);
        List<ReasonCode> reasons = new ArrayList<>();

        if (rules == null) {
            // A product with no rules cannot be decided by policy, and guessing
            // is worse than asking a person.
            return refer(List.of(ReasonCode.MANUAL_REVIEW_REQUIRED), null, null, null);
        }

        // Identity first, for every product. Failed checks refuse whatever else
        // is true, and that is not subject to the flag below: the flag governs
        // how an *unfinished* check is treated, never a failed one.
        KycVerdict kyc = assessKyc(kycStatus);
        if (kyc == KycVerdict.REJECTED) {
            return reject(List.of(ReasonCode.KYC_REJECTED), null, null);
        }

        boolean kycHolds = kyc == KycVerdict.INCOMPLETE && rules.isRequiresVerifiedKyc();

        if (!ApplicationTransitions.isCreditProduct(type)) {
            // A deposit account lends nothing. Identity is the whole test.
            return kycHolds
                    ? refer(List.of(ReasonCode.KYC_REVIEW_REQUIRED), null, null, null)
                    : approve(application.getRequestedAmount(), List.of(), null, null);
        }

        boolean refer = kycHolds;
        if (refer) {
            // Not a refusal: unfinished identity checks may yet be finished.
            reasons.add(ReasonCode.KYC_REVIEW_REQUIRED);
        }

        int score = creditScore != null ? creditScore : 0;
        if (score < rules.getMinCreditScore()) {
            reasons.add(ReasonCode.CREDIT_SCORE_BELOW_MINIMUM);
            return reject(reasons, null, null);
        }
        if (score < rules.getReferBelowCreditScore()) {
            refer = true;
            reasons.add(ReasonCode.CREDIT_SCORE_BELOW_MINIMUM);
        }

        BigDecimal requested = application.getRequestedAmount();
        if (rules.getMaxAmount() != null) {
            if (requested == null) {
                return refer(with(reasons, ReasonCode.INSUFFICIENT_INFORMATION), null, null, null);
            }
            if (requested.compareTo(rules.getMaxAmount()) > 0) {
                reasons.add(ReasonCode.REQUEST_AMOUNT_ABOVE_POLICY);
                return reject(reasons, null, null);
            }
        }

        if (!rules.getTermsMonths().isEmpty()) {
            Integer term = application.getTermMonths();
            if (term == null) {
                return refer(with(reasons, ReasonCode.INSUFFICIENT_INFORMATION), null, null, null);
            }
            if (!rules.getTermsMonths().contains(term)) {
                reasons.add(ReasonCode.TERM_NOT_SUPPORTED);
                return reject(reasons, null, null);
            }
        }

        BigDecimal dti = null;
        if (rules.getMaxDti() != null) {
            dti = debtToIncome(application);
            if (dti == null) {
                return refer(with(reasons, ReasonCode.INSUFFICIENT_INFORMATION), null, null, null);
            }
            if (dti.compareTo(rules.getMaxDti()) > 0) {
                reasons.add(ReasonCode.DTI_ABOVE_POLICY);
                return reject(reasons, dti, null);
            }
            if (rules.getReferAboveDti() != null && dti.compareTo(rules.getReferAboveDti()) > 0) {
                refer = true;
                reasons.add(ReasonCode.DTI_ABOVE_POLICY);
            }
        }

        BigDecimal ltv = null;
        if (rules.getMaxLtv() != null) {
            ltv = loanToValue(application);
            if (ltv == null) {
                return refer(with(reasons, ReasonCode.INSUFFICIENT_INFORMATION), dti, null, null);
            }
            if (ltv.compareTo(rules.getMaxLtv()) > 0) {
                reasons.add(ReasonCode.LTV_ABOVE_POLICY);
                return reject(reasons, dti, ltv);
            }
        }

        if (refer) {
            return refer(reasons, requested, dti, ltv);
        }
        return approve(requested, reasons, dti, ltv);
    }

    /**
     * Gross monthly income is the annual figure over twelve, and DTI is the
     * monthly obligations over that. Null when either input is missing or
     * income is zero — a ratio over nothing is not a large ratio, it is no
     * ratio, and treating it as a large one would refuse the application for a
     * gap in the form.
     */
    private BigDecimal debtToIncome(Application application) {
        BigDecimal annualIncome = application.getAnnualIncome();
        BigDecimal monthlyDebt = application.getMonthlyDebtObligations();
        if (annualIncome == null || monthlyDebt == null || annualIncome.signum() <= 0) {
            return null;
        }
        BigDecimal grossMonthlyIncome = annualIncome.divide(MONTHS_PER_YEAR, RATIO_SCALE, RoundingMode.HALF_UP);
        if (grossMonthlyIncome.signum() <= 0) {
            return null;
        }
        return monthlyDebt.divide(grossMonthlyIncome, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * What is borrowed over what secures it, net of any deposit. A down payment
     * reduces the loan, not the asset, which is why it is subtracted here
     * rather than added to the denominator.
     */
    private BigDecimal loanToValue(Application application) {
        BigDecimal assetValue = application.getAssetValue();
        BigDecimal requested = application.getRequestedAmount();
        if (assetValue == null || requested == null || assetValue.signum() <= 0) {
            return null;
        }
        BigDecimal downPayment = application.getDownPayment() != null
                ? application.getDownPayment() : BigDecimal.ZERO;
        BigDecimal borrowed = requested.subtract(downPayment);
        if (borrowed.signum() <= 0) {
            // Nothing is being borrowed against the asset.
            return BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
        }
        return borrowed.divide(assetValue, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Not-rejected is not the same as verified.
     *
     * <p>Treating anything that is not an outright rejection as good enough is
     * how an unfinished identity check comes to be read as a completed one.
     * Only the verified state clears; a rejection refuses; everything else —
     * pending, in review, missing entirely — refers.
     */
    private KycVerdict assessKyc(String kycStatus) {
        if (kycStatus == null) {
            return KycVerdict.INCOMPLETE;
        }
        return switch (kycStatus.toUpperCase()) {
            // user-service calls the cleared state APPROVED. VERIFIED is
            // accepted too so that a rename there cannot silently turn every
            // cleared customer back into an unverified one.
            case "APPROVED", "VERIFIED" -> KycVerdict.VERIFIED;
            case "REJECTED" -> KycVerdict.REJECTED;
            // PENDING and IN_REVIEW: started, not finished.
            default -> KycVerdict.INCOMPLETE;
        };
    }

    private enum KycVerdict { VERIFIED, INCOMPLETE, REJECTED }

    private static List<ReasonCode> with(List<ReasonCode> reasons, ReasonCode extra) {
        List<ReasonCode> copy = new ArrayList<>(reasons);
        copy.add(extra);
        return copy;
    }

    private UnderwritingDecision approve(BigDecimal amount, List<ReasonCode> reasons,
                                         BigDecimal dti, BigDecimal ltv) {
        return new UnderwritingDecision(UnderwritingDecision.Outcome.APPROVE,
                List.copyOf(reasons), amount, dti, ltv, policy.getVersion());
    }

    private UnderwritingDecision reject(List<ReasonCode> reasons, BigDecimal dti, BigDecimal ltv) {
        return new UnderwritingDecision(UnderwritingDecision.Outcome.REJECT,
                List.copyOf(reasons), null, dti, ltv, policy.getVersion());
    }

    private UnderwritingDecision refer(List<ReasonCode> reasons, BigDecimal amount,
                                       BigDecimal dti, BigDecimal ltv) {
        List<ReasonCode> stated = reasons.isEmpty()
                ? List.of(ReasonCode.MANUAL_REVIEW_REQUIRED) : List.copyOf(reasons);
        return new UnderwritingDecision(UnderwritingDecision.Outcome.REFER,
                stated, amount, dti, ltv, policy.getVersion());
    }
}
