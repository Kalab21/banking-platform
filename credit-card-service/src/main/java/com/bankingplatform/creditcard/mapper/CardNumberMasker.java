package com.bankingplatform.creditcard.mapper;

/**
 * Reduces a card number to what may safely leave the service.
 *
 * <p>Kept as a separate, side-effect-free helper so the masking rule has one
 * definition and can be unit-tested directly. Nothing here logs its input.
 */
public final class CardNumberMasker {

    private static final String UNKNOWN_MASK = "•••• •••• •••• ••••";

    private CardNumberMasker() {
    }

    /**
     * Returns a display form showing only the final four digits.
     *
     * <p>Anything too short to mask safely collapses to a fully masked value
     * rather than being echoed back.
     */
    public static String mask(String cardNumber) {
        String last4 = last4(cardNumber);
        return last4 == null ? UNKNOWN_MASK : "•••• •••• •••• " + last4;
    }

    /** The last four digits, or {@code null} when there are not enough to take. */
    public static String last4(String cardNumber) {
        if (cardNumber == null) return null;
        String digits = cardNumber.replaceAll("\\D", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }
}
