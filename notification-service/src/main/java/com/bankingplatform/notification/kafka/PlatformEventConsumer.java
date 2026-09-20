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
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import com.bankingplatform.common.events.UserLifecycleEvents;
import com.bankingplatform.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 *
 * <p>Every handler still checks {@code userId} for null. The type makes the
 * field required in Java, not in the JSON: a record written by an older
 * producer during a rolling deploy, or replayed from before this change,
 * deserializes with a null there. {@code notifications.user_id} is
 * {@code NOT NULL}, so passing one through would throw out of the listener,
 * and with no dead-letter topic configured yet the container would retry the
 * same offset ten times and block the partition. Skipping is the same
 * behaviour as before; what has changed is that the field is now populated,
 * so the skip is the exception rather than the rule.
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
    private final NotificationService notificationService;

    @KafkaListener(topics = Topics.ACCOUNT_EVENTS, groupId = "notification-service")
    @Transactional
    public void onAccountEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof AccountCreated || event instanceof OverdraftTriggered)) {
            return;
        }
        if (!processedEvents.claim("notification-service:account", event.eventId())) {
            return;
        }
        if (event instanceof AccountCreated e && e.userId() != null) {
            notificationService.onAccountCreated(e.userId());
        } else if (event instanceof OverdraftTriggered e && e.userId() != null) {
            // Handled here, on the topic it is actually published to. The
            // overdraft notice used to be looked for on transaction-events,
            // under a different field name, so it had never been sent.
            notificationService.onOverdraft(e.userId(), orZero(e.overdraftAmount()));
        }
    }

    @KafkaListener(topics = Topics.TRANSACTION_EVENTS, groupId = "notification-service")
    @Transactional
    public void onTransactionEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof TransactionCreated || event instanceof TransferCompleted)) {
            return;
        }
        if (!processedEvents.claim("notification-service:transaction", event.eventId())) {
            return;
        }
        if (event instanceof TransactionCreated e && e.userId() != null) {
            notificationService.onLargeTransaction(e.userId(), e.amount(), e.transactionRef());
        } else if (event instanceof TransferCompleted e && e.userId() != null) {
            notificationService.onLargeTransaction(e.userId(), e.amount(), e.transactionRef());
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "notification-service")
    @Transactional
    public void onPaymentEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof PaymentCompleted || event instanceof PaymentFailed)) {
            return;
        }
        if (!processedEvents.claim("notification-service:payment", event.eventId())) {
            return;
        }
        // A null owner means account-service could not be asked who owns the
        // paying account; the notification is skipped rather than guessed.
        if (event instanceof PaymentCompleted e && e.userId() != null) {
            notificationService.onPaymentCompleted(e.userId(), orZero(e.amount()), e.paymentRef());
        } else if (event instanceof PaymentFailed e && e.userId() != null) {
            notificationService.onPaymentFailed(e.userId());
        }
    }

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "notification-service")
    @Transactional
    public void onApplicationEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof ApplicationApproved || event instanceof ApplicationRejected)) {
            return;
        }
        if (!processedEvents.claim("notification-service:application", event.eventId())) {
            return;
        }
        if (event instanceof ApplicationApproved e && e.userId() != null) {
            notificationService.onApplicationApproved(e.userId(), e.productType());
        } else if (event instanceof ApplicationRejected e && e.userId() != null) {
            notificationService.onApplicationRejected(e.userId(), e.productType());
        }
    }

    @KafkaListener(topics = Topics.CREDIT_CARD_EVENTS, groupId = "notification-service")
    @Transactional
    public void onCreditCardEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof CreditCardCreated || event instanceof CreditCardStatementGenerated)) {
            return;
        }
        if (!processedEvents.claim("notification-service:credit-card", event.eventId())) {
            return;
        }
        if (event instanceof CreditCardCreated e && e.userId() != null) {
            // last4, never the card number.
            notificationService.onCreditCardIssued(e.userId(), e.last4());
        } else if (event instanceof CreditCardStatementGenerated e && e.userId() != null) {
            notificationService.onCreditCardStatementGenerated(e.userId(), e.statementDate());
        }
    }

    @KafkaListener(topics = Topics.LOAN_EVENTS, groupId = "notification-service")
    @Transactional
    public void onLoanEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof LoanDisbursed || event instanceof LoanPaidOff)) {
            return;
        }
        if (!processedEvents.claim("notification-service:loan", event.eventId())) {
            return;
        }
        // LOAN_PAYMENT_DUE and LOAN_PAYMENT_MISSED used to be handled here.
        // Nothing has ever produced them: there is no scheduler that looks
        // for an instalment coming due or going unpaid. The handlers are gone
        // rather than left looking like working features. Adding the
        // delinquency job is a change in its own right, and the notification
        // copy is still in NotificationService waiting for it.
        if (event instanceof LoanDisbursed e && e.userId() != null) {
            notificationService.onLoanDisbursed(e.userId(), orZero(e.principal()), e.loanId());
        } else if (event instanceof LoanPaidOff e && e.userId() != null) {
            notificationService.onLoanPaidOff(e.userId(), e.loanId());
        }
    }

    @KafkaListener(topics = Topics.USER_EVENTS, groupId = "notification-service")
    @Transactional
    public void onUserEvent(DomainEvent event) {
        // Below the type check: an event this service does not act on should
        // leave no trace, and UnknownEvent is documented as no side effect and
        // no error. Claiming first would write a row for every record on the
        // topic and grow the table at full throughput.
        if (!(event instanceof UserLifecycleEvents.KycApproved || event instanceof UserLifecycleEvents.KycRejected || event instanceof UserLifecycleEvents.TwoFactorEnabled || event instanceof UserLifecycleEvents.TwoFactorDisabled)) {
            return;
        }
        if (!processedEvents.claim("notification-service:user", event.eventId())) {
            return;
        }
        if (event instanceof UserLifecycleEvents.KycApproved e && e.userId() != null) {
            notificationService.onKycApproved(e.userId());
        } else if (event instanceof UserLifecycleEvents.KycRejected e && e.userId() != null) {
            notificationService.onKycRejected(e.userId());
        } else if (event instanceof UserLifecycleEvents.TwoFactorEnabled e && e.userId() != null) {
            notificationService.onTwoFaEnabled(e.userId());
        } else if (event instanceof UserLifecycleEvents.TwoFactorDisabled e && e.userId() != null) {
            notificationService.onTwoFaDisabled(e.userId());
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
