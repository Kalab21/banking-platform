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
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import com.bankingplatform.statistics.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Keeps the platform counters.
 *
 * <p>Two of these were counting nothing. Per-user transaction statistics read
 * a {@code userId} the transaction events never carried, so every transaction
 * was attributed to a null user; and the submitted-applications counter
 * listened for an event whose producer method existed but was never called.
 *
 * <p>Every handler claims the event id in the same transaction as its effect,
 * so a redelivery — which retry now makes ordinary — does the work once. The
 * claim is per handler rather than per service, because several handlers here
 * consume the same topic and each has to act on an event exactly once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformEventConsumer {

    private final ProcessedEventGuard processedEvents;
    private final StatisticsService statisticsService;

    @KafkaListener(topics = Topics.ACCOUNT_EVENTS, groupId = "statistics-service")
    @Transactional
    public void onAccountEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof AccountCreated)) {
            return;
        }
        if (!processedEvents.claim("statistics-service:account", event.eventId())) {
            return;
        }
        if (event instanceof AccountCreated e) {
            statisticsService.onAccountCreated(e.userId());
        }
    }

    @KafkaListener(topics = Topics.TRANSACTION_EVENTS, groupId = "statistics-service")
    @Transactional
    public void onTransactionEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof TransactionCreated || event instanceof TransferCompleted)) {
            return;
        }
        if (!processedEvents.claim("statistics-service:transaction", event.eventId())) {
            return;
        }
        if (event instanceof TransactionCreated e) {
            statisticsService.onTransactionCreated(e.userId(), orZero(e.amount()));
        } else if (event instanceof TransferCompleted e) {
            statisticsService.onTransactionCreated(e.userId(), orZero(e.amount()));
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "statistics-service")
    @Transactional
    public void onPaymentEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof PaymentCompleted || event instanceof PaymentFailed)) {
            return;
        }
        if (!processedEvents.claim("statistics-service:payment", event.eventId())) {
            return;
        }
        if (event instanceof PaymentCompleted e) {
            statisticsService.onPaymentCompleted(e.payerAccountId(), orZero(e.amount()));
        } else if (event instanceof PaymentFailed) {
            statisticsService.onPaymentFailed();
        }
    }

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "statistics-service")
    @Transactional
    public void onApplicationEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof ApplicationSubmitted || event instanceof ApplicationApproved || event instanceof ApplicationRejected)) {
            return;
        }
        if (!processedEvents.claim("statistics-service:application", event.eventId())) {
            return;
        }
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
    @Transactional
    public void onCreditCardEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof CreditCardCreated || event instanceof CreditCardTransactionCompleted)) {
            return;
        }
        if (!processedEvents.claim("statistics-service:credit-card", event.eventId())) {
            return;
        }
        if (event instanceof CreditCardCreated e) {
            statisticsService.onCreditCardCreated(e.userId());
        } else if (event instanceof CreditCardTransactionCompleted e) {
            statisticsService.onCcTransaction(e.cardId(), orZero(e.amount()));
        }
    }

    @KafkaListener(topics = Topics.LOAN_EVENTS, groupId = "statistics-service")
    @Transactional
    public void onLoanEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof LoanDisbursed || event instanceof LoanRepaymentMade || event instanceof LoanPaidOff)) {
            return;
        }
        if (!processedEvents.claim("statistics-service:loan", event.eventId())) {
            return;
        }
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
