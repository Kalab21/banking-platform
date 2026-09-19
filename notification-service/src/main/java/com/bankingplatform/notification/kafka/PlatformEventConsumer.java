package com.bankingplatform.notification.kafka;

import com.bankingplatform.common.events.AccountCreated;
import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.ApplicationRejected;
import com.bankingplatform.common.events.CreditCardCreated;
import com.bankingplatform.common.events.CreditCardStatementGenerated;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.LoanDisbursed;
import com.bankingplatform.common.events.LoanPaidOff;
import com.bankingplatform.common.events.OverdraftTriggered;
import com.bankingplatform.common.events.PaymentCompleted;
import com.bankingplatform.common.events.PaymentFailed;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import com.bankingplatform.common.events.UserLifecycleEvents;
import com.bankingplatform.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Turns platform events into customer notifications.
 *
 * <p>Rewritten against the shared event types. Reading fields out of a map by
 * name is what made most of these notifications dead: the handler read
 * {@code userId}, found null because the producer never sent it, and returned
 * without doing anything. A typed event cannot be missing a field the
 * consumer compiles against.
 *
 * <p>Matching on the event type replaces string comparison, so a
 * producer and a consumer can no longer disagree about a spelling — the
 * statement notification had been matching {@code STATEMENT_GENERATED}
 * against a producer emitting {@code CREDIT_CARD_STATEMENT_GENERATED}.
 *
 * <p>An event this service does not act on is simply not matched. That
 * includes {@code UnknownEvent}, which is how a type added by a newer
 * producer arrives here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = Topics.ACCOUNT_EVENTS, groupId = "notification-service")
    public void onAccountEvent(DomainEvent event) {
        if (event instanceof AccountCreated e) {
            notificationService.onAccountCreated(e.userId());
        } else if (event instanceof OverdraftTriggered e) {
            // Handled here, on the topic it is actually published to. The
            // overdraft notice used to be looked for on transaction-events,
            // under a different field name, so it had never been sent.
            notificationService.onOverdraft(e.userId(), orZero(e.overdraftAmount()));
        }
    }

    @KafkaListener(topics = Topics.TRANSACTION_EVENTS, groupId = "notification-service")
    public void onTransactionEvent(DomainEvent event) {
        if (event instanceof TransactionCreated e) {
            notificationService.onLargeTransaction(e.userId(), e.amount(), e.transactionRef());
        } else if (event instanceof TransferCompleted e) {
            notificationService.onLargeTransaction(e.userId(), e.amount(), e.transactionRef());
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "notification-service")
    public void onPaymentEvent(DomainEvent event) {
        // A null owner means account-service could not be asked who owns the
        // paying account; the notification is skipped rather than guessed.
        if (event instanceof PaymentCompleted e && e.userId() != null) {
            notificationService.onPaymentCompleted(e.userId(), orZero(e.amount()), e.paymentRef());
        } else if (event instanceof PaymentFailed e && e.userId() != null) {
            notificationService.onPaymentFailed(e.userId());
        }
    }

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "notification-service")
    public void onApplicationEvent(DomainEvent event) {
        if (event instanceof ApplicationApproved e) {
            notificationService.onApplicationApproved(e.userId(), e.productType());
        } else if (event instanceof ApplicationRejected e) {
            notificationService.onApplicationRejected(e.userId(), e.productType());
        }
    }

    @KafkaListener(topics = Topics.CREDIT_CARD_EVENTS, groupId = "notification-service")
    public void onCreditCardEvent(DomainEvent event) {
        if (event instanceof CreditCardCreated e) {
            // last4, never the card number.
            notificationService.onCreditCardIssued(e.userId(), e.last4());
        } else if (event instanceof CreditCardStatementGenerated e) {
            notificationService.onCreditCardStatementGenerated(e.userId(), e.statementDate());
        }
    }

    @KafkaListener(topics = Topics.LOAN_EVENTS, groupId = "notification-service")
    public void onLoanEvent(DomainEvent event) {
        // LOAN_PAYMENT_DUE and LOAN_PAYMENT_MISSED used to be handled here.
        // Nothing has ever produced them: there is no scheduler that looks
        // for an instalment coming due or going unpaid. The handlers are gone
        // rather than left looking like working features. Adding the
        // delinquency job is a change in its own right, and the notification
        // copy is still in NotificationService waiting for it.
        if (event instanceof LoanDisbursed e) {
            notificationService.onLoanDisbursed(e.userId(), orZero(e.principal()), e.loanId());
        } else if (event instanceof LoanPaidOff e) {
            notificationService.onLoanPaidOff(e.userId(), e.loanId());
        }
    }

    @KafkaListener(topics = Topics.USER_EVENTS, groupId = "notification-service")
    public void onUserEvent(DomainEvent event) {
        if (event instanceof UserLifecycleEvents.KycApproved e) {
            notificationService.onKycApproved(e.userId());
        } else if (event instanceof UserLifecycleEvents.KycRejected e) {
            notificationService.onKycRejected(e.userId());
        } else if (event instanceof UserLifecycleEvents.TwoFactorEnabled e) {
            notificationService.onTwoFaEnabled(e.userId());
        } else if (event instanceof UserLifecycleEvents.TwoFactorDisabled e) {
            notificationService.onTwoFaDisabled(e.userId());
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
