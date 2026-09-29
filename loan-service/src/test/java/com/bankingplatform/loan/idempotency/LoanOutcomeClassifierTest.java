package com.bankingplatform.loan.idempotency;

import com.bankingplatform.common.security.AccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Loan outcome classification")
class LoanOutcomeClassifierTest {

    @Test
    @DisplayName("a funding account refused by the ownership check moved no money")
    void ownershipRefusalMovedNothing() {
        // It settled the key as UNKNOWN, so every retry of the request answered 504.
        assertThat(new LoanOutcomeClassifier().movedNoMoney(new AccessDeniedException("not the borrower's account")))
                .isTrue();
    }
}
