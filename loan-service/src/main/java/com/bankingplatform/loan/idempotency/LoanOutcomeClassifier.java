package com.bankingplatform.loan.idempotency;

import com.bankingplatform.common.idempotency.OutcomeClassifier;
import com.bankingplatform.loan.exception.LoanNotActiveException;
import com.bankingplatform.loan.exception.ResourceNotFoundException;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * Whether a failed repayment is known to have left every balance untouched.
 *
 * <p>Answers "no" by default. Being wrong in that direction spends an
 * idempotency key that could have been retried; being wrong in the other
 * direction debits a customer twice for one instalment.
 *
 * <p>A repayment debits an account in {@code account-service} before it
 * touches the loan, so "nothing happened" here means nothing happened in
 * either place, and most of the cases below are about how far that call got.
 */
@Component
public class LoanOutcomeClassifier implements OutcomeClassifier {

    @Override
    public boolean movedNoMoney(RuntimeException failure) {
        // Refused by this service before the account was called: an unknown
        // loan, a loan that is not active, or no instalment left to pay. The
        // transaction rolls back and nothing was debited.
        if (failure instanceof ResourceNotFoundException
                || failure instanceof LoanNotActiveException
                || failure instanceof IllegalStateException
                || failure instanceof IllegalArgumentException) {
            return true;
        }

        if (failure instanceof FeignException feign) {
            // A 4xx is account-service declining -- insufficient funds, a
            // frozen account, an unknown id. It declined instead of debiting,
            // and the loan side rolls back with it.
            //
            // A 5xx says nothing about whether the debit landed, and the loan
            // side has already rolled back, so the two can disagree. That is
            // the case UNKNOWN exists for.
            return feign.status() >= 400 && feign.status() < 500;
        }

        // Anything else, including a timeout: the debit may have landed.
        return false;
    }
}
