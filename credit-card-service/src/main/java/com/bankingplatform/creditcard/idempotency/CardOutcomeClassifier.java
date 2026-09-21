package com.bankingplatform.creditcard.idempotency;

import com.bankingplatform.common.idempotency.OutcomeClassifier;
import com.bankingplatform.creditcard.exception.CardNotActiveException;
import com.bankingplatform.creditcard.exception.InsufficientCreditException;
import com.bankingplatform.creditcard.exception.ResourceNotFoundException;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * Whether a failed card operation is known to have left every balance
 * untouched.
 *
 * <p>Answers "no" by default. Being wrong in that direction spends an
 * idempotency key that could have been retried; being wrong in the other
 * direction charges a card twice.
 *
 * <p>Two of the three card operations also move money in
 * {@code account-service} — a cash advance credits an account, a payment
 * debits one — so "nothing happened" here means nothing happened in either
 * place, and most of the cases below are about how far that call got.
 */
@Component
public class CardOutcomeClassifier implements OutcomeClassifier {

    @Override
    public boolean movedNoMoney(RuntimeException failure) {
        // Refused by this service before anything was written or called: an
        // unknown card, a frozen one, or not enough credit. The transaction
        // rolls back and the account service was never contacted.
        if (failure instanceof ResourceNotFoundException
                || failure instanceof CardNotActiveException
                || failure instanceof InsufficientCreditException
                || failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException) {
            return true;
        }

        if (failure instanceof FeignException feign) {
            // A 4xx is account-service declining — an unknown account,
            // insufficient funds, a frozen account. It declined instead of
            // applying, and the card transaction rolls back with it.
            //
            // A 5xx says nothing about whether the balance changed, and the
            // card side has already rolled back, so the two can disagree.
            // That is the case the UNKNOWN status exists for.
            return feign.status() >= 400 && feign.status() < 500;
        }

        // Anything else, including a timeout: the call may have landed.
        return false;
    }
}
