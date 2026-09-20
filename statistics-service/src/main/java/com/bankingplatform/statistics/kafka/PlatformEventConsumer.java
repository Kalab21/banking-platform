package com.bankingplatform.statistics.kafka;

import com.bankingplatform.common.events.AccountCreated;
import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.ApplicationRejected;
import com.bankingplatform.common.events.ApplicationSubmitted;
import com.bankingplatform.common.events.CreditCardCreated;
import com.bankingplatform.common.events.CreditCardTransactionCompleted;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.LoanDisbursed;
import com.bankingplatform.common.events.LoanPaidOff;
import com.bankingplatform.common.events.LoanRepaymentMade;
import com.bankingplatform.common.events.PaymentCompleted;
import com.bankingplatform.common.events.PaymentFailed;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import com.bankingplatform.statistics.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Keeps the platform counters.
 *
 * <p>Two of these were counting nothing. Per-user transaction statistics read
 * a {@code userId} the transaction events never carried, so every transaction
 * was attributed to a null user; and the submitted-applications counter
 * listened for an event whose producer method existed but was never called.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformEventConsumer {

    private final StatisticsService statisticsService;

    @KafkaListener(topics = Topics.ACCOUNT_EVENTS, groupId = "statistics-service")
    public void onAccountEvent(DomainEvent event) {
        if (event instanceof AccountCreated e) {
            statisticsService.onAccountCreated(e.userId());
        }
    }

    @KafkaListener(topics = Topics.TRANSACTION_EVENTS, groupId = "statistics-service")
    public void onTransactionEvent(DomainEvent event) {
        if (event instanceof TransactionCreated e) {
            statisticsService.onTransactionCreated(e.userId(), orZero(e.amount()));
        } else if (event instanceof TransferCompleted e) {
            statisticsService.onTransactionCreated(e.userId(), orZero(e.amount()));
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "statistics-service")
    public void onPaymentEvent(DomainEvent event) {
        if (event instanceof PaymentCompleted e) {
            statisticsService.onPaymentCompleted(e.payerAccountId(), orZero(e.amount()));
        } else if (event instanceof PaymentFailed) {
            statisticsService.onPaymentFailed();
        }
    }

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "statistics-service")
    public void onApplicationEvent(DomainEvent event) {
        // The submitted counter had never moved: nothing published the event.
        if (event instanceof ApplicationSubmitted) {
            statisticsService.onApplicationSubmitted();
        } else if (event instanceof ApplicationApproved) {
            statisticsService.onApplicationApproved();
        } else if (event instanceof ApplicationRejected) {
            statisticsService.onApplicationRejected();
        }
    }

    @KafkaListener(topics = Topics.CREDIT_CARD_EVENTS, groupId = "statistics-service")
    public void onCreditCardEvent(DomainEvent event) {
        if (event instanceof CreditCardCreated e) {
            statisticsService.onCreditCardCreated(e.userId());
        } else if (event instanceof CreditCardTransactionCompleted e) {
            statisticsService.onCcTransaction(e.cardId(), orZero(e.amount()));
        }
    }

    @KafkaListener(topics = Topics.LOAN_EVENTS, groupId = "statistics-service")
    public void onLoanEvent(DomainEvent event) {
        if (event instanceof LoanDisbursed e) {
            statisticsService.onLoanDisbursed(e.userId(), orZero(e.principal()));
        } else if (event instanceof LoanRepaymentMade e) {
            statisticsService.onLoanRepayment(orZero(e.amount()));
        } else if (event instanceof LoanPaidOff e) {
            statisticsService.onLoanPaidOff(e.userId());
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
