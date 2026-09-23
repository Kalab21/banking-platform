package com.bankingplatform.creditcard.service;

import com.bankingplatform.creditcard.dto.request.*;
import com.bankingplatform.creditcard.dto.response.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface CreditCardService {
    CreditCardResponse createCard(CreateCreditCardRequest request);
    CreditCardResponse getCard(Long cardId);
    List<CreditCardResponse> getCardsByUser(Long userId);
    CreditCardResponse updateStatus(Long cardId, UpdateCardStatusRequest request, boolean actingAsStaff);

    CreditCardTransactionResponse purchase(Long cardId, PurchaseRequest request);
    CreditCardTransactionResponse cashAdvance(Long cardId, CashAdvanceRequest request);
    CreditCardTransactionResponse makePayment(Long cardId, CardPaymentRequest request);

    Page<CreditCardTransactionResponse> getTransactions(Long cardId, Pageable pageable);
    CreditCardTransactionResponse getTransaction(String transactionRef);

    CreditCardStatementResponse generateStatement(Long cardId);
    List<CreditCardStatementResponse> getStatements(Long cardId);

    void chargeInterest();
}
