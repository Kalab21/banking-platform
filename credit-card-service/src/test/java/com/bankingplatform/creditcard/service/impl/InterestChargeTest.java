package com.bankingplatform.creditcard.service.impl;

import com.bankingplatform.creditcard.client.AccountClient;
import com.bankingplatform.creditcard.kafka.producer.CreditCardEventProducer;
import com.bankingplatform.creditcard.mapper.CreditCardMapper;
import com.bankingplatform.creditcard.mapper.CreditCardStatementMapper;
import com.bankingplatform.creditcard.mapper.CreditCardTransactionMapper;
import com.bankingplatform.creditcard.model.CardStatus;
import com.bankingplatform.creditcard.model.CreditCard;
import com.bankingplatform.creditcard.repository.CreditCardRepository;
import com.bankingplatform.creditcard.repository.CreditCardStatementRepository;
import com.bankingplatform.creditcard.repository.CreditCardTransactionRepository;
import com.bankingplatform.creditcard.service.AccountOwnershipGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Daily interest follows the debt, not the card's spending state.
 */
@DisplayName("Daily card interest")
class InterestChargeTest {

    private CreditCard card(CardStatus status) {
        return CreditCard.builder()
                .id(1L).userId(7L).status(status)
                .creditLimit(new BigDecimal("3000.00"))
                .currentBalance(new BigDecimal("1000.00"))
                .availableCredit(new BigDecimal("2000.00"))
                .dailyRate(new BigDecimal("0.000600"))
                .build();
    }

    @ParameterizedTest(name = "a {0} card with a balance is charged interest")
    @EnumSource(value = CardStatus.class, names = {"ACTIVE", "CUSTOMER_FROZEN", "SYSTEM_BLOCKED"})
    void balanceCarryingCardsAccrue(CardStatus status) {
        // A freeze used to switch interest off: the job read ACTIVE cards only.
        CreditCardRepository cards = Mockito.mock(CreditCardRepository.class);
        CreditCardTransactionRepository txs = Mockito.mock(CreditCardTransactionRepository.class);
        CreditCard card = card(status);
        when(cards.findByStatusIn(CardStatus.CARRIES_BALANCE)).thenReturn(List.of(card));
        when(cards.findByIdForUpdate(1L)).thenReturn(Optional.of(card));
        when(txs.save(any())).thenAnswer(i -> i.getArgument(0));

        new CreditCardServiceImpl(cards, txs, Mockito.mock(CreditCardStatementRepository.class),
                Mockito.mock(AccountClient.class), Mockito.mock(AccountOwnershipGuard.class),
                Mockito.mock(CreditCardEventProducer.class), Mockito.mock(CreditCardMapper.class),
                Mockito.mock(CreditCardTransactionMapper.class), Mockito.mock(CreditCardStatementMapper.class))
                .chargeInterest();

        assertThat(card.getCurrentBalance()).isEqualByComparingTo("1000.60");
    }

    @ParameterizedTest(name = "a {0} card is not in the interest run")
    @EnumSource(value = CardStatus.class, names = {"DEFAULTED", "CLOSED"})
    void endedCardsAreNotInTheRun(CardStatus status) {
        assertThat(CardStatus.CARRIES_BALANCE).doesNotContain(status);
    }
}
