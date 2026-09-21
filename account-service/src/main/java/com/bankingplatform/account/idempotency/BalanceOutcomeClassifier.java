package com.bankingplatform.account.idempotency;

import com.bankingplatform.common.idempotency.OutcomeClassifier;
import org.springframework.stereotype.Component;

/**
 * Whether a failed balance update left the balance untouched.
 *
 * <p>Here, always. This is the service that owns the balance: the update runs
 * inside one local transaction that locks the row, does the arithmetic,
 * writes the audit row and records the outbox event. A {@code RuntimeException}
 * escaping it rolls all of that back together, and there is no second system
 * whose state could disagree.
 *
 * <p>That makes this the one classifier on the platform that can honestly say
 * "nothing happened" to every failure. Its callers cannot: a timeout on the
 * way here leaves <em>them</em> unable to tell whether this transaction
 * committed, which is the whole reason they send a key.
 *
 * <p>Worth stating rather than defaulting to, because the conservative
 * default would mark every refused debit {@code UNKNOWN} and spend a key on
 * it. An insufficient-funds refusal is an answer, not an unknown, and a
 * caller that fixes the balance should be able to retry under the same key.
 */
@Component
public class BalanceOutcomeClassifier implements OutcomeClassifier {

    @Override
    public boolean movedNoMoney(RuntimeException failure) {
        return true;
    }
}
