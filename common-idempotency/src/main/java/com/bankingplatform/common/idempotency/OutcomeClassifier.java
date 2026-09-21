package com.bankingplatform.common.idempotency;

/**
 * Whether a failed attempt is <em>known</em> to have left every balance
 * untouched.
 *
 * <p>This is the judgement the whole mechanism turns on, and it is the one
 * part that cannot be shared: what a failure implies depends on what the
 * service called and how far the call got. A card service that refused a
 * purchase locally moved nothing; a transfer that debited one account and
 * failed to credit the other moved a great deal.
 *
 * <p><b>The default answer is "not known".</b> Being wrong in that direction
 * spends an idempotency key that could have been retried, and a customer sees
 * an error for something that did not happen. Being wrong in the other
 * direction retries a debit that already landed. Those are not comparable, so
 * anything a service has not explicitly recognised is treated as unknown.
 */
@FunctionalInterface
public interface OutcomeClassifier {

    /**
     * @param failure what the operation threw
     * @return true only when this service can say the failure left no balance
     *         changed anywhere
     */
    boolean movedNoMoney(RuntimeException failure);

    /**
     * Treats every failure as having an unknown effect.
     *
     * <p>The safe classifier, and the one a service gets if it supplies
     * nothing. It is correct but pessimistic: keys are spent on failures that
     * changed nothing, and those rows have to be reconciled by hand. A service
     * moving real money is expected to replace it.
     */
    static OutcomeClassifier conservative() {
        return failure -> false;
    }
}
