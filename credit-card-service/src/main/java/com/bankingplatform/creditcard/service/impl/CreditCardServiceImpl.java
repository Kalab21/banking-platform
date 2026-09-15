package com.bankingplatform.creditcard.service.impl;

import com.bankingplatform.creditcard.client.AccountClient;
import com.bankingplatform.creditcard.dto.request.*;
import com.bankingplatform.creditcard.dto.response.*;
import com.bankingplatform.creditcard.exception.CardNotActiveException;
import com.bankingplatform.creditcard.exception.InsufficientCreditException;
import com.bankingplatform.creditcard.exception.ResourceNotFoundException;
import com.bankingplatform.creditcard.kafka.producer.CreditCardEventProducer;
import com.bankingplatform.creditcard.mapper.CreditCardMapper;
import com.bankingplatform.creditcard.mapper.CreditCardStatementMapper;
import com.bankingplatform.creditcard.mapper.CreditCardTransactionMapper;
import com.bankingplatform.creditcard.model.*;
import com.bankingplatform.creditcard.repository.CreditCardRepository;
import com.bankingplatform.creditcard.repository.CreditCardStatementRepository;
import com.bankingplatform.creditcard.repository.CreditCardTransactionRepository;
import com.bankingplatform.creditcard.service.CreditCardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CreditCardServiceImpl implements CreditCardService {

    private static final BigDecimal CASH_ADVANCE_FEE_RATE = new BigDecimal("0.05");
    private static final BigDecimal MIN_PAYMENT_RATE = new BigDecimal("0.02");
    private static final BigDecimal MIN_PAYMENT_FLOOR = new BigDecimal("25.00");
    private static final int REWARDS_RATE_PER_DOLLAR = 1;

    private final CreditCardRepository cardRepository;
    private final CreditCardTransactionRepository txRepository;
    private final CreditCardStatementRepository statementRepository;
    private final AccountClient accountClient;
    private final CreditCardEventProducer eventProducer;
    private final CreditCardMapper cardMapper;
    private final CreditCardTransactionMapper txMapper;
    private final CreditCardStatementMapper statementMapper;

    @Override
    public CreditCardResponse createCard(CreateCreditCardRequest request) {
        BigDecimal dailyRate = request.getApr()
                .divide(new BigDecimal("36500"), 10, RoundingMode.HALF_UP);

        CreditCard card = CreditCard.builder()
                .cardNumber(generateCardNumber())
                .userId(request.getUserId())
                .applicationId(request.getApplicationId())
                .cardType(request.getCardType())
                .creditLimit(request.getCreditLimit())
                .availableCredit(request.getCreditLimit())
                .currentBalance(BigDecimal.ZERO)
                .statementBalance(BigDecimal.ZERO)
                .minimumPaymentDue(BigDecimal.ZERO)
                .apr(request.getApr())
                .dailyRate(dailyRate)
                .billingCycleDay(1)
                .status(CardStatus.ACTIVE)
                .currency("USD")
                .rewardsPoints(0)
                .linkedAccountId(request.getLinkedAccountId())
                .build();

        card = cardRepository.save(card);
        eventProducer.publishCardCreated(card.getId(), card.getUserId(), card.getCardType().name());
        log.info("Created credit card id={} for userId={}", card.getId(), card.getUserId());
        return cardMapper.toResponse(card);
    }

    @Override
    @Transactional(readOnly = true)
    public CreditCardResponse getCard(Long cardId) {
        return cardMapper.toResponse(findCard(cardId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditCardResponse> getCardsByUser(Long userId) {
        return cardRepository.findByUserId(userId).stream()
                .map(cardMapper::toResponse)
                .toList();
    }

    @Override
    public CreditCardResponse updateStatus(Long cardId, UpdateCardStatusRequest request) {
        CreditCard card = findCard(cardId);
        card.setStatus(request.getStatus());
        return cardMapper.toResponse(cardRepository.save(card));
    }

    @Override
    public CreditCardTransactionResponse purchase(Long cardId, PurchaseRequest request) {
        CreditCard card = findCard(cardId);
        requireActive(card);
        requireSufficientCredit(card, request.getAmount());

        card.setCurrentBalance(card.getCurrentBalance().add(request.getAmount()));
        card.setAvailableCredit(card.getAvailableCredit().subtract(request.getAmount()));
        card.setRewardsPoints(card.getRewardsPoints() + request.getAmount().intValue() * REWARDS_RATE_PER_DOLLAR);
        cardRepository.save(card);

        CreditCardTransaction tx = saveTx(card, CreditCardTransactionType.PURCHASE,
                request.getAmount(), request.getDescription(),
                request.getMerchantName(), request.getMerchantCategory());

        eventProducer.publishTransactionCompleted(card.getId(), tx.getTransactionRef(),
                "PURCHASE", request.getAmount(), card.getAvailableCredit());
        return txMapper.toResponse(tx);
    }

    @Override
    public CreditCardTransactionResponse cashAdvance(Long cardId, CashAdvanceRequest request) {
        CreditCard card = findCard(cardId);
        requireActive(card);

        BigDecimal fee = request.getAmount().multiply(CASH_ADVANCE_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCharge = request.getAmount().add(fee);
        requireSufficientCredit(card, totalCharge);

        // Credit target account
        accountClient.credit(request.getTargetAccountId(), request.getAmount(), "Cash advance from credit card");

        card.setCurrentBalance(card.getCurrentBalance().add(totalCharge));
        card.setAvailableCredit(card.getAvailableCredit().subtract(totalCharge));
        cardRepository.save(card);

        saveTx(card, CreditCardTransactionType.FEE, fee, "Cash advance fee", null, null);
        CreditCardTransaction tx = saveTx(card, CreditCardTransactionType.CASH_ADVANCE,
                request.getAmount(), "Cash advance to account " + request.getTargetAccountId(), null, null);

        eventProducer.publishTransactionCompleted(card.getId(), tx.getTransactionRef(),
                "CASH_ADVANCE", request.getAmount(), card.getAvailableCredit());
        return txMapper.toResponse(tx);
    }

    @Override
    public CreditCardTransactionResponse makePayment(Long cardId, CardPaymentRequest request) {
        CreditCard card = findCard(cardId);

        BigDecimal payAmount = request.getAmount().min(card.getCurrentBalance());
        if (payAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("No balance to pay");
        }

        if (request.getSourceAccountId() != null) {
            accountClient.debit(request.getSourceAccountId(), payAmount, "Credit card payment");
        }

        card.setCurrentBalance(card.getCurrentBalance().subtract(payAmount));
        card.setAvailableCredit(card.getAvailableCredit().add(payAmount));

        if (card.getCurrentBalance().compareTo(BigDecimal.ZERO) == 0) {
            card.setMinimumPaymentDue(BigDecimal.ZERO);
        } else if (payAmount.compareTo(card.getMinimumPaymentDue()) >= 0) {
            card.setMinimumPaymentDue(BigDecimal.ZERO);
        }

        cardRepository.save(card);
        CreditCardTransaction tx = saveTx(card, CreditCardTransactionType.PAYMENT,
                payAmount, "Card payment", null, null);

        eventProducer.publishTransactionCompleted(card.getId(), tx.getTransactionRef(),
                "PAYMENT", payAmount, card.getAvailableCredit());
        return txMapper.toResponse(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CreditCardTransactionResponse> getTransactions(Long cardId, Pageable pageable) {
        findCard(cardId);
        return txRepository.findByCreditCardIdOrderByCreatedAtDesc(cardId, pageable)
                .map(txMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public CreditCardTransactionResponse getTransaction(String transactionRef) {
        return txMapper.toResponse(txRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionRef)));
    }

    @Override
    public CreditCardStatementResponse generateStatement(Long cardId) {
        CreditCard card = findCard(cardId);
        LocalDate today = LocalDate.now();

        if (statementRepository.existsByCreditCardIdAndStatementDate(cardId, today)) {
            return statementMapper.toResponse(
                    statementRepository.findByCreditCardIdAndStatementDate(cardId, today).orElseThrow());
        }

        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();

        BigDecimal purchases = txRepository.sumByCardIdAndTypeBetween(
                cardId, CreditCardTransactionType.PURCHASE, startOfDay.minusDays(30), endOfDay);
        BigDecimal payments = txRepository.sumByCardIdAndTypeBetween(
                cardId, CreditCardTransactionType.PAYMENT, startOfDay.minusDays(30), endOfDay);
        BigDecimal interest = txRepository.sumByCardIdAndTypeBetween(
                cardId, CreditCardTransactionType.INTEREST_CHARGE, startOfDay.minusDays(30), endOfDay);
        BigDecimal fees = txRepository.sumByCardIdAndTypeBetween(
                cardId, CreditCardTransactionType.FEE, startOfDay.minusDays(30), endOfDay);

        BigDecimal closingBalance = card.getCurrentBalance();
        BigDecimal minPayment = closingBalance.multiply(MIN_PAYMENT_RATE)
                .setScale(2, RoundingMode.HALF_UP).max(MIN_PAYMENT_FLOOR);
        if (closingBalance.compareTo(MIN_PAYMENT_FLOOR) < 0) minPayment = closingBalance;

        LocalDate dueDate = today.plusDays(25);

        CreditCardStatement statement = CreditCardStatement.builder()
                .creditCard(card)
                .statementDate(today)
                .openingBalance(closingBalance.add(purchases).subtract(payments))
                .closingBalance(closingBalance)
                .totalPurchases(purchases)
                .totalPayments(payments)
                .interestCharged(interest)
                .feesCharged(fees)
                .rewardsEarned(0)
                .minimumPayment(minPayment)
                .paymentDueDate(dueDate)
                .paidInFull(closingBalance.compareTo(BigDecimal.ZERO) == 0)
                .build();

        card.setStatementBalance(closingBalance);
        card.setMinimumPaymentDue(minPayment);
        card.setPaymentDueDate(dueDate);
        cardRepository.save(card);

        statement = statementRepository.save(statement);
        eventProducer.publishStatementGenerated(cardId, statement.getId(), today.toString());
        return statementMapper.toResponse(statement);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditCardStatementResponse> getStatements(Long cardId) {
        findCard(cardId);
        return statementRepository.findByCreditCardIdOrderByStatementDateDesc(cardId).stream()
                .map(statementMapper::toResponse)
                .toList();
    }

    @Override
    public void chargeInterest() {
        List<CreditCard> activeCards = cardRepository.findByStatus(CardStatus.ACTIVE);
        for (CreditCard card : activeCards) {
            if (card.getCurrentBalance().compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal interestCharge = card.getCurrentBalance()
                    .multiply(card.getDailyRate())
                    .setScale(2, RoundingMode.HALF_UP);

            if (interestCharge.compareTo(new BigDecimal("0.01")) < 0) continue;

            card.setCurrentBalance(card.getCurrentBalance().add(interestCharge));
            card.setAvailableCredit(card.getCreditLimit().subtract(card.getCurrentBalance()));
            if (card.getAvailableCredit().compareTo(BigDecimal.ZERO) < 0) {
                card.setAvailableCredit(BigDecimal.ZERO);
            }
            cardRepository.save(card);
            saveTx(card, CreditCardTransactionType.INTEREST_CHARGE,
                    interestCharge, "Daily interest charge", null, null);
        }
        log.info("Charged daily interest on {} active cards", activeCards.size());
    }

    // --- helpers ---

    private CreditCard findCard(Long cardId) {
        return cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit card not found: " + cardId));
    }

    private void requireActive(CreditCard card) {
        if (card.getStatus() != CardStatus.ACTIVE) {
            throw new CardNotActiveException("Card is not active: " + card.getStatus());
        }
    }

    private void requireSufficientCredit(CreditCard card, BigDecimal amount) {
        if (card.getAvailableCredit().compareTo(amount) < 0) {
            throw new InsufficientCreditException(
                    "Insufficient credit. Available: " + card.getAvailableCredit() + ", Required: " + amount);
        }
    }

    private CreditCardTransaction saveTx(CreditCard card, CreditCardTransactionType type,
                                          BigDecimal amount, String description,
                                          String merchantName, String merchantCategory) {
        return txRepository.save(CreditCardTransaction.builder()
                .creditCard(card)
                .transactionRef(generateRef())
                .type(type)
                .amount(amount)
                .description(description)
                .merchantName(merchantName)
                .merchantCategory(merchantCategory)
                .status("COMPLETED")
                .build());
    }

    private String generateRef() {
        String ref;
        do {
            ref = UUID.randomUUID().toString();
        } while (txRepository.existsByTransactionRef(ref));
        return ref;
    }

    private String generateCardNumber() {
        String number;
        do {
            StringBuilder sb = new StringBuilder("4");
            for (int i = 0; i < 15; i++) {
                sb.append((int) (Math.random() * 10));
            }
            number = sb.toString();
        } while (cardRepository.existsByCardNumber(number));
        return number;
    }
}
