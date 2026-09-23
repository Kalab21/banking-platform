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
         * APR bands, best rate first. The first band whose minimum score the
         * applicant meets is the rate offered. Empty for a product that is not
         * priced by rate.
         */
        private List<Band> aprBands = new ArrayList<>();

        /**
         * Credit-card tiers and limits, best first. The first band the
         * applicant meets decides tier and limit together, because a tier with
         * someone else's limit is not a product Northbank offers.
         */
        private List<CardBand> cardBands = new ArrayList<>();

        /**
         * Whether an <em>unfinished</em> identity check holds this product back.
         *
         * <p>On for credit, off for deposit accounts. A customer registers with
         * KYC {@code PENDING} and reaches {@code IN_REVIEW} by submitting
         * documents; only a staff review moves them to {@code APPROVED}. Until
         * that review existed there was nothing to wait for, so arming this
         * would have referred every credit application ever submitted — not a
         * safer bank, just one that issued nothing. Staff review exists now.
         *
         * <p>Deposit accounts stay off deliberately. Opening an empty account
         * lends nothing and is how a customer starts; requiring completed
         * identity checks first would make the first step of the demo
         * impossible, and there is no credit risk to hold back.
         *
         * <p>A <em>failed</em> check refuses regardless of this flag.
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

        public List<Band> getAprBands() {
            return aprBands;
        }

        public void setAprBands(List<Band> aprBands) {
            this.aprBands = aprBands;
        }

        public List<CardBand> getCardBands() {
            return cardBands;
        }

        public void setCardBands(List<CardBand> cardBands) {
            this.cardBands = cardBands;
        }

        /** The rate for this score, or null when the product is not rate-priced. */
        public BigDecimal aprFor(int creditScore) {
            return aprBands.stream()
                    .filter(band -> creditScore >= band.getMinCreditScore())
                    .map(Band::getApr)
                    .findFirst()
                    .orElse(aprBands.isEmpty() ? null : aprBands.get(aprBands.size() - 1).getApr());
        }

        /** The tier and limit for this score, or null for a product without them. */
        public CardBand cardBandFor(int creditScore) {
            return cardBands.stream()
                    .filter(band -> creditScore >= band.getMinCreditScore())
                    .findFirst()
                    .orElse(cardBands.isEmpty() ? null : cardBands.get(cardBands.size() - 1));
        }

        /** A product that lends nothing has no amount, term, DTI or LTV rules. */
        boolean isLending() {
            return maxAmount != null || !termsMonths.isEmpty() || maxDti != null;
        }
    }

    /** One rate band: meet the score, get the rate. */
    public static class Band {
        private int minCreditScore;
        private BigDecimal apr;

        public Band() {
        }

        public Band(int minCreditScore, String apr) {
            this.minCreditScore = minCreditScore;
            this.apr = new BigDecimal(apr);
        }

        public int getMinCreditScore() {
            return minCreditScore;
        }

        public void setMinCreditScore(int minCreditScore) {
            this.minCreditScore = minCreditScore;
        }

        public BigDecimal getApr() {
            return apr;
        }

        public void setApr(BigDecimal apr) {
            this.apr = apr;
        }
    }

    /** One card band: tier, limit and rate move together. */
    public static class CardBand {
        private int minCreditScore;
        private String tier;
        private BigDecimal creditLimit;
        private BigDecimal apr;

        public CardBand() {
        }

        public CardBand(int minCreditScore, String tier, String creditLimit, String apr) {
            this.minCreditScore = minCreditScore;
            this.tier = tier;
            this.creditLimit = new BigDecimal(creditLimit);
            this.apr = new BigDecimal(apr);
        }

        public int getMinCreditScore() {
            return minCreditScore;
        }

        public void setMinCreditScore(int minCreditScore) {
            this.minCreditScore = minCreditScore;
        }

        public String getTier() {
            return tier;
        }

        public void setTier(String tier) {
            this.tier = tier;
        }

        public BigDecimal getCreditLimit() {
            return creditLimit;
        }

        public void setCreditLimit(BigDecimal creditLimit) {
            this.creditLimit = creditLimit;
        }

        public BigDecimal getApr() {
            return apr;
        }

        public void setApr(BigDecimal apr) {
            this.apr = apr;
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
        card.setRequiresVerifiedKyc(true);
        card.setReferBelowCreditScore(680);
        card.setMaxDti(new BigDecimal("0.45"));
        card.setReferAboveDti(new BigDecimal("0.40"));
        // Tier, limit and rate move together: a tier carrying someone else's
        // limit is not a product Northbank offers.
        card.setCardBands(List.of(
                new CardBand(800, "PLATINUM", "10000", "14.99"),
                new CardBand(720, "GOLD", "5000", "18.99"),
                new CardBand(680, "STANDARD", "3000", "21.99"),
                new CardBand(0, "STANDARD", "1000", "24.99")));
        map.put(ApplicationType.CREDIT_CARD, card);

        ProductRules personal = new ProductRules();
        personal.setMinCreditScore(600);
        personal.setRequiresVerifiedKyc(true);
        personal.setReferBelowCreditScore(640);
        personal.setMaxDti(new BigDecimal("0.43"));
        personal.setReferAboveDti(new BigDecimal("0.38"));
        personal.setMaxAmount(new BigDecimal("50000"));
        personal.setTermsMonths(List.of(12, 24, 36, 48, 60));
        personal.setAprBands(List.of(
                new Band(720, "10.99"),
                new Band(0, "14.99")));
        map.put(ApplicationType.PERSONAL_LOAN, personal);

        ProductRules auto = new ProductRules();
        auto.setMinCreditScore(620);
        auto.setRequiresVerifiedKyc(true);
        auto.setReferBelowCreditScore(660);
        auto.setMaxDti(new BigDecimal("0.45"));
        auto.setReferAboveDti(new BigDecimal("0.40"));
        auto.setMaxLtv(new BigDecimal("1.20"));
        auto.setMaxAmount(new BigDecimal("100000"));
        auto.setTermsMonths(List.of(36, 48, 60, 72));
        auto.setAprBands(List.of(
                new Band(720, "7.99"),
                new Band(0, "9.99")));
        map.put(ApplicationType.AUTO_LOAN, auto);

        ProductRules mortgage = new ProductRules();
        mortgage.setMinCreditScore(700);
        mortgage.setRequiresVerifiedKyc(true);
        mortgage.setReferBelowCreditScore(740);
        mortgage.setMaxDti(new BigDecimal("0.36"));
        mortgage.setReferAboveDti(new BigDecimal("0.31"));
        mortgage.setMaxLtv(new BigDecimal("0.95"));
        mortgage.setMaxAmount(new BigDecimal("1000000"));
        mortgage.setTermsMonths(List.of(180, 240, 360));
        mortgage.setAprBands(List.of(
                new Band(760, "6.50"),
                new Band(0, "7.25")));
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
