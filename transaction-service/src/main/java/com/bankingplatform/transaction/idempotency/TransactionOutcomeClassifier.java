package com.bankingplatform.transaction.idempotency;

import com.bankingplatform.common.idempotency.OutcomeClassifier;
import com.bankingplatform.transaction.exception.AccountCallTimeoutException;
import com.bankingplatform.transaction.exception.ResourceNotFoundException;
import com.bankingplatform.transaction.exception.TransactionException;
import com.bankingplatform.transaction.exception.TransferPartiallyAppliedException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.stereotype.Component;

/**
 * Whether a failed money movement in this service is known to have left every
 * balance untouched.
 *
 * <p>Answers "no" by default. Being wrong in that direction spends an
 * idempotency key that could have been retried; being wrong in the other
 * direction retries a debit that already happened.
 *
 * <p>Service-specific by nature: every case below is a statement about how
 * far a call to {@code account-service} got, which only this service knows.
 */
@Component
public class TransactionOutcomeClassifier implements OutcomeClassifier {

    @Override
    public boolean movedNoMoney(RuntimeException failure) {
        Throwable cause = failure;
        if (failure instanceof NoFallbackAvailableException && failure.getCause() != null) {
            cause = failure.getCause();
        }

        // The debit landed and the credit did not. Money moved, by definition.
        if (cause instanceof TransferPartiallyAppliedException) {
            return false;
        }
        // The circuit was open, so the call never left this service.
        if (cause instanceof CallNotPermittedException) {
            return true;
        }
        // The call left and the answer did not come back.
        if (cause instanceof AccountCallTimeoutException) {
            return false;
        }
        // Refused here, before the account service was called at all.
        if (cause instanceof TransactionException || cause instanceof ResourceNotFoundException) {
            return true;
        }
        if (cause instanceof FeignException feign) {
            // A 4xx is the account service declining — insufficient funds, a
            // frozen account, an unknown id. It declined instead of applying.
            // A 5xx says nothing about whether the balance changed.
            return feign.status() >= 400 && feign.status() < 500;
        }
        return false;
    }
}
