package com.bankingplatform.creditcard.idempotency;

import com.bankingplatform.common.security.AccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Card outcome classification")
class CardOutcomeClassifierTest {

    @Test
    @DisplayName("a funding account refused by the ownership check moved no money")
    void ownershipRefusalMovedNothing() {
        assertThat(new CardOutcomeClassifier().movedNoMoney(new AccessDeniedException("not the cardholder's account")))
                .isTrue();
    }
}
