package com.bankingplatform.creditcard.service.impl;

import com.bankingplatform.creditcard.client.AccountClient;
import com.bankingplatform.creditcard.dto.request.CardPaymentRequest;
import com.bankingplatform.creditcard.kafka.producer.CreditCardEventProducer;
import com.bankingplatform.creditcard.mapper.CreditCardMapper;
import com.bankingplatform.creditcard.mapper.CreditCardStatementMapper;
import com.bankingplatform.creditcard.mapper.CreditCardTransactionMapper;
import com.bankingplatform.creditcard.model.CardStatus;
import com.bankingplatform.creditcard.model.CreditCard;
import com.bankingplatform.creditcard.model.CreditCardStatement;
import com.bankingplatform.creditcard.model.CreditCardTransactionType;
import com.bankingplatform.creditcard.repository.CreditCardRepository;
import com.bankingplatform.creditcard.repository.CreditCardStatementRepository;
import com.bankingplatform.creditcard.repository.CreditCardTransactionRepository;
import com.bankingplatform.creditcard.service.AccountOwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Card balance arithmetic")
class CardArithmeticTest {

    private CreditCardRepository cards;
    private CreditCardTransactionRepository txs;
    private CreditCardStatementRepository statements;
    private CreditCardServiceImpl service;
    private CreditCard card;

    @BeforeEach
    void setUp() {
        cards = Mockito.mock(CreditCardRepository.class);
        txs = Mockito.mock(CreditCardTransactionRepository.class);
        statements = Mockito.mock(CreditCardStatementRepository.class);
        card = CreditCard.builder()
                .id(1L).userId(7L).status(CardStatus.ACTIVE)
                .creditLimit(new BigDecimal("1000.00"))
                .currentBalance(new BigDecimal("1000.00"))
                .availableCredit(BigDecimal.ZERO)
                .minimumPaymentDue(BigDecimal.ZERO)
                .dailyRate(new BigDecimal("0.010000"))
                .build();
        when(cards.findByIdForUpdate(1L)).thenReturn(Optional.of(card));
        when(cards.findByStatusIn(CardStatus.CARRIES_BALANCE)).thenReturn(List.of(card));
        when(txs.save(any())).thenAnswer(i -> i.getArgument(0));
        when(statements.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new CreditCardServiceImpl(cards, txs, statements,
                Mockito.mock(AccountClient.class), Mockito.mock(AccountOwnershipGuard.class),
                Mockito.mock(CreditCardEventProducer.class), Mockito.mock(CreditCardMapper.class),
                Mockito.mock(CreditCardTransactionMapper.class), Mockito.mock(CreditCardStatementMapper.class));
    }

    @Test
    @DisplayName("paying off a card that interest took past its limit restores the limit, not more")
    void availableCreditNeverExceedsTheLimit() {
        service.chargeInterest();
        assertThat(card.getCurrentBalance()).isEqualByComparingTo("1010.00");
        assertThat(card.getAvailableCredit()).isEqualByComparingTo("0.00");

        CardPaymentRequest payment = new CardPaymentRequest();
        payment.setAmount(new BigDecimal("1010.00"));
        payment.setSourceAccountId(5L);
        service.makePayment(1L, payment);

        assertThat(card.getCurrentBalance()).isEqualByComparingTo("0.00");
        // Was 1010.00: the clamped zero plus the whole payment.
        assertThat(card.getAvailableCredit()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a statement's opening balance is the closing balance less the period's charges plus its payments")
    void openingBalanceWorksBackFromTheClose() {
        card.setCurrentBalance(new BigDecimal("665.00"));
        when(statements.existsByCreditCardIdAndStatementDate(eq(1L), any())).thenReturn(false);
        when(txs.sumByCardIdAndTypeBetween(anyLong(), eq(CreditCardTransactionType.PURCHASE), any(), any()))
                .thenReturn(new BigDecimal("200.00"));
        when(txs.sumByCardIdAndTypeBetween(anyLong(), eq(CreditCardTransactionType.CASH_ADVANCE), any(), any()))
                .thenReturn(new BigDecimal("0.00"));
        when(txs.sumByCardIdAndTypeBetween(anyLong(), eq(CreditCardTransactionType.INTEREST_CHARGE), any(), any()))
                .thenReturn(new BigDecimal("5.00"));
        when(txs.sumByCardIdAndTypeBetween(anyLong(), eq(CreditCardTransactionType.FEE), any(), any()))
                .thenReturn(new BigDecimal("10.00"));
        when(txs.sumByCardIdAndTypeBetween(anyLong(), eq(CreditCardTransactionType.PAYMENT), any(), any()))
                .thenReturn(new BigDecimal("50.00"));

        service.generateStatement(1L);

        ArgumentCaptor<CreditCardStatement> saved = ArgumentCaptor.forClass(CreditCardStatement.class);
        verify(statements).save(saved.capture());
        // 665 - 200 - 5 - 10 + 50. It used to read 665 + 200 - 50 = 815,
        // an opening balance above the close after a month of spending.
        assertThat(saved.getValue().getOpeningBalance()).isEqualByComparingTo("500.00");
        assertThat(saved.getValue().getClosingBalance()).isEqualByComparingTo("665.00");
    }
}
