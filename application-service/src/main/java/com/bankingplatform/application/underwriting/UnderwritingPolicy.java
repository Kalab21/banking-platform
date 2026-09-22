package com.bankingplatform.application.underwriting;

import com.bankingplatform.application.model.ApplicationType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Northbank's demo lending policy: the thresholds a decision is measured against.
 *
 * <p>This is not real lending policy and does not pretend to be. There is no
 * bureau behind it, no vendor and no model — the inputs are synthetic figures
 * the customer stated and a synthetic score the platform holds. It exists so
 * that a decision is <em>reproducible</em>: the same application against the
 * same policy version decides the same way, and the figures it was measured
 * against are recorded rather than recomputed later from data that has moved on.
 *
 * <p>Every number lives here rather than in the service, because a threshold
 * scattered through a method body cannot be versioned, and a decision that
 * cannot name the policy it was taken under cannot be explained afterwards.
 */
@ConfigurationProperties(prefix = "northbank.underwriting")
public class UnderwritingPolicy {

    /**
     * Stamped onto every decision. Change a threshold below and change this,
     * so an old decision still says what it was measured against.
     */
    private String version = "2026.09";

    /** Per-product rules. A product with no entry here cannot be underwritten. */
    private Map<ApplicationType, ProductRules> products = defaults();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<ApplicationType, ProductRules> getProducts() {
        return products;
    }

    public void setProducts(Map<ApplicationType, ProductRules> products) {
        this.products = products;
    }

    public ProductRules rulesFor(ApplicationType type) {
        return products.get(type);
    }

    /**
     * The rules for one product.
     *
     * <p>A deposit account has no credit rules at all: it is not lending, and
     * the only question is whether the customer is who they say they are.
     */
    public static class ProductRules {

        /** Below this the application is refused. */
        private int minCreditScore;

        /** At or above this it is inside policy on score alone. */
        private int referBelowCreditScore;

        /** Monthly debt over gross monthly income, as a fraction. */
        private BigDecimal maxDti;

        /** Above this fraction of DTI but inside the maximum, a human looks. */
        private BigDecimal referAboveDti;

        /** Loan over asset value, for secured lending. Null where not secured. */
        private BigDecimal maxLtv;

        /** The most this product lends. Null for a product with no amount. */
        private BigDecimal maxAmount;

        /** Terms this product offers. Empty where a term does not apply. */
        private List<Integer> termsMonths = new ArrayList<>();

        /**
         * Whether an <em>unfinished</em> identity check holds this product back.
         *
         * <p>Off everywhere today, and deliberately so. A customer registers
         * with KYC {@code PENDING} and submitting documents only reaches
         * {@code IN_REVIEW}; nothing moves them to {@code APPROVED} except a
         * staff review, and no staff review path exists yet. Arming this now
         * would refer every credit application ever submitted, which is not a
         * safer bank, only a bank that issues nothing.
         *
         * <p>It is switched on in the PR that gives staff a way to approve
         * KYC. The semantics it governs are already implemented and tested:
         * {@code PENDING} and {@code IN_REVIEW} are read as unfinished rather
         * than as good enough, and this flag decides only what is done about
         * that. A <em>failed</em> check refuses regardless of this flag.
         */
        private boolean requiresVerifiedKyc = false;

        public int getMinCreditScore() {
            return minCreditScore;
        }

        public void setMinCreditScore(int minCreditScore) {
            this.minCreditScore = minCreditScore;
        }

        public int getReferBelowCreditScore() {
            return referBelowCreditScore;
        }

        public void setReferBelowCreditScore(int referBelowCreditScore) {
            this.referBelowCreditScore = referBelowCreditScore;
        }

        public BigDecimal getMaxDti() {
            return maxDti;
        }

        public void setMaxDti(BigDecimal maxDti) {
            this.maxDti = maxDti;
        }

        public BigDecimal getReferAboveDti() {
            return referAboveDti;
        }

        public void setReferAboveDti(BigDecimal referAboveDti) {
            this.referAboveDti = referAboveDti;
        }

        public BigDecimal getMaxLtv() {
            return maxLtv;
        }

        public void setMaxLtv(BigDecimal maxLtv) {
            this.maxLtv = maxLtv;
        }

        public BigDecimal getMaxAmount() {
            return maxAmount;
        }

        public void setMaxAmount(BigDecimal maxAmount) {
            this.maxAmount = maxAmount;
        }

        public List<Integer> getTermsMonths() {
            return termsMonths;
        }

        public void setTermsMonths(List<Integer> termsMonths) {
            this.termsMonths = termsMonths;
        }

        public boolean isRequiresVerifiedKyc() {
            return requiresVerifiedKyc;
        }

        public void setRequiresVerifiedKyc(boolean requiresVerifiedKyc) {
            this.requiresVerifiedKyc = requiresVerifiedKyc;
        }

        /** A product that lends nothing has no amount, term, DTI or LTV rules. */
        boolean isLending() {
            return maxAmount != null || !termsMonths.isEmpty() || maxDti != null;
        }
    }

    /**
     * The shipped policy. Expressed here rather than only in YAML so that a
     * unit test and a service both see the same numbers without a Spring
     * context, and so the defaults are reviewable in one place.
     */
    private static Map<ApplicationType, ProductRules> defaults() {
        Map<ApplicationType, ProductRules> map = new EnumMap<>(ApplicationType.class);

        map.put(ApplicationType.CHECKING_ACCOUNT, depositAccount());
        map.put(ApplicationType.SAVINGS_ACCOUNT, depositAccount());

        ProductRules card = new ProductRules();
        card.setMinCreditScore(650);
        card.setReferBelowCreditScore(680);
        card.setMaxDti(new BigDecimal("0.45"));
        card.setReferAboveDti(new BigDecimal("0.40"));
        map.put(ApplicationType.CREDIT_CARD, card);

        ProductRules personal = new ProductRules();
        personal.setMinCreditScore(600);
        personal.setReferBelowCreditScore(640);
        personal.setMaxDti(new BigDecimal("0.43"));
        personal.setReferAboveDti(new BigDecimal("0.38"));
        personal.setMaxAmount(new BigDecimal("50000"));
        personal.setTermsMonths(List.of(12, 24, 36, 48, 60));
        map.put(ApplicationType.PERSONAL_LOAN, personal);

        ProductRules auto = new ProductRules();
        auto.setMinCreditScore(620);
        auto.setReferBelowCreditScore(660);
        auto.setMaxDti(new BigDecimal("0.45"));
        auto.setReferAboveDti(new BigDecimal("0.40"));
        auto.setMaxLtv(new BigDecimal("1.20"));
        auto.setMaxAmount(new BigDecimal("100000"));
        auto.setTermsMonths(List.of(36, 48, 60, 72));
        map.put(ApplicationType.AUTO_LOAN, auto);

        ProductRules mortgage = new ProductRules();
        mortgage.setMinCreditScore(700);
        mortgage.setReferBelowCreditScore(740);
        mortgage.setMaxDti(new BigDecimal("0.36"));
        mortgage.setReferAboveDti(new BigDecimal("0.31"));
        mortgage.setMaxLtv(new BigDecimal("0.95"));
        mortgage.setMaxAmount(new BigDecimal("1000000"));
        mortgage.setTermsMonths(List.of(180, 240, 360));
        map.put(ApplicationType.MORTGAGE, mortgage);

        return map;
    }

    /**
     * A deposit account is not lending. No score, no ratio and no amount — the
     * only question policy asks is whether identity is established, and that is
     * asked of every product.
     */
    private static ProductRules depositAccount() {
        ProductRules rules = new ProductRules();
        rules.setMinCreditScore(0);
        rules.setReferBelowCreditScore(0);
        return rules;
    }
}
