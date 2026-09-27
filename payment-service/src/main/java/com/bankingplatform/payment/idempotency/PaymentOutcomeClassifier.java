package com.bankingplatform.payment.idempotency;

import com.bankingplatform.common.idempotency.OutcomeClassifier;
import com.bankingplatform.payment.exception.PaymentException;
import com.bankingplatform.payment.exception.ResourceNotFoundException;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * Which failed payment attempts provably moved no money, so their key may be
 * reused.
 */
@Component
public class PaymentOutcomeClassifier implements OutcomeClassifier {

    @Override
    public boolean movedNoMoney(RuntimeException failure) {
        // Refused here before transaction-service was called.
        if (failure instanceof PaymentException
                || failure instanceof ResourceNotFoundException
                || failure instanceof IllegalArgumentException) {
            return true;
        }
        // transaction-service declining (insufficient funds, a frozen or
        // unknown account) declined instead of moving money. A 5xx says
        // nothing about whether the movement landed.
        if (failure instanceof FeignException feign) {
            return feign.status() >= 400 && feign.status() < 500;
        }
        return false;
    }
}
