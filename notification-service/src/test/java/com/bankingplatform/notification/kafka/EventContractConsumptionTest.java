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
import com.bankingplatform.common.events.TransactionCreated;
import com.bankingplatform.common.events.TransferCompleted;
import com.bankingplatform.common.events.UserLifecycleEvents;
import com.bankingplatform.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The producer and this consumer agreeing, through JSON.
 *
 * <p>Each case builds the event with the <em>producer's</em> factory method,
 * serializes it, deserializes it the way this service's Kafka configuration
 * does — {@code DomainEvent} with type headers off, routed by the
 * {@code eventType} field — and hands the result to the real listener. If a
 * producer drops a field or renames an event, these fail.
 *
 * <p>Every case here is a notification that <b>did not work</b> before this
 * change. They are written as "the workflow triggers" rather than "the field
 * is present", because a field being present was never the thing that was
 * wrong: the consumer ran, read null, and returned.
 */
@DisplayName("Notification event contracts")
class EventContractConsumptionTest {

    private ObjectMapper mapper;
    private NotificationService notificationService;
    private PlatformEventConsumer consumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        notificationService = Mockito.mock(NotificationService.class);
        consumer = new PlatformEventConsumer(notificationService);
    }

    /** Exactly what Kafka would hand the listener: bytes in, DomainEvent out. */
    private DomainEvent overTheWire(DomainEvent published) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(published), DomainEvent.class);
    }

    @Nested
    @DisplayName("notifications that were dead before")
    class PreviouslyDead {

        @Test
        @DisplayName("an overdraft now reaches the customer, from the topic it is published on")
        void overdraft() throws Exception {
            // Published to account-events with overdraftAmount; the consumer
            // used to look on transaction-events for a field called amount.
            consumer.onAccountEvent(overTheWire(
                    OverdraftTriggered.of(3L, 42L, new BigDecimal("50.00"))));

            verify(notificationService).onOverdraft(42L, new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("a large transaction now reaches the customer")
        void largeTransaction() throws Exception {
            consumer.onTransactionEvent(overTheWire(TransactionCreated.of(
                    1L, "ref-1", 3L, 42L, "DEPOSIT",
                    new BigDecimal("9000.00"), new BigDecimal("9100.00"))));

            verify(notificationService).onLargeTransaction(42L, new BigDecimal("9000.00"), "ref-1");
        }

        @Test
        @DisplayName("a completed transfer reaches the customer under the same reference field")
        void transfer() throws Exception {
            consumer.onTransactionEvent(overTheWire(TransferCompleted.of(
                    "debit-1", "credit-1", 3L, 42L, 4L, new BigDecimal("25.00"))));

            verify(notificationService).onLargeTransaction(42L, new BigDecimal("25.00"), "debit-1");
        }

        @Test
        @DisplayName("a completed payment reaches the payer")
        void paymentCompleted() throws Exception {
            consumer.onPaymentEvent(overTheWire(PaymentCompleted.of(
                    1L, "pay-1", 3L, 42L, 4L, new BigDecimal("30.00"), "INTERNAL")));

            verify(notificationService).onPaymentCompleted(42L, new BigDecimal("30.00"), "pay-1");
        }

        @Test
        @DisplayName("a failed payment reaches the payer")
        void paymentFailed() throws Exception {
            consumer.onPaymentEvent(overTheWire(PaymentFailed.of(1L, "pay-1", 3L, 42L)));

            verify(notificationService).onPaymentFailed(42L);
        }

        @Test
        @DisplayName("a statement notice is sent, now the two sides spell the event the same way")
        void statement() throws Exception {
            consumer.onCreditCardEvent(overTheWire(
                    CreditCardStatementGenerated.of(5L, 42L, 9L, "2026-09-01")));

            verify(notificationService).onCreditCardStatementGenerated(42L, "2026-09-01");
        }

        @Test
        @DisplayName("a card issuance names four digits instead of throwing")
        void cardIssued() throws Exception {
            consumer.onCreditCardEvent(overTheWire(
                    CreditCardCreated.of(5L, 42L, "GOLD", "3823")));

            verify(notificationService).onCreditCardIssued(42L, "3823");
        }

        @Test
        @DisplayName("a rejected application names the product it was for")
        void rejection() throws Exception {
            // Read productType, which this event never carried, so every
            // rejection notice said "your product application".
            consumer.onApplicationEvent(overTheWire(
                    ApplicationRejected.of(7L, 42L, "CREDIT_CARD", "Score too low")));

            verify(notificationService).onApplicationRejected(42L, "CREDIT_CARD");
        }

        @Test
        @DisplayName("KYC and two-factor changes reach the customer, now that something publishes them")
        void userLifecycle() throws Exception {
            consumer.onUserEvent(overTheWire(UserLifecycleEvents.KycApproved.of(42L)));
            consumer.onUserEvent(overTheWire(UserLifecycleEvents.KycRejected.of(43L)));
            consumer.onUserEvent(overTheWire(UserLifecycleEvents.TwoFactorEnabled.of(44L)));
            consumer.onUserEvent(overTheWire(UserLifecycleEvents.TwoFactorDisabled.of(45L)));

            verify(notificationService).onKycApproved(42L);
            verify(notificationService).onKycRejected(43L);
            verify(notificationService).onTwoFaEnabled(44L);
            verify(notificationService).onTwoFaDisabled(45L);
        }
    }

    @Nested
    @DisplayName("notifications that already worked")
    class StillWorking {

        @Test
        @DisplayName("an opened account still notifies, without carrying its number")
        void accountCreated() throws Exception {
            String json = mapper.writeValueAsString(AccountCreated.of(3L, 42L, "CHECKING"));
            org.assertj.core.api.Assertions.assertThat(json).doesNotContain("accountNumber");

            consumer.onAccountEvent(mapper.readValue(json, DomainEvent.class));

            verify(notificationService).onAccountCreated(42L);
        }

        @Test
        @DisplayName("approval and loan events still notify")
        void approvalsAndLoans() throws Exception {
            consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                    7L, 42L, "PERSONAL_LOAN", 8L, 720, new BigDecimal("10000"))));
            consumer.onLoanEvent(overTheWire(LoanDisbursed.of(8L, 42L, new BigDecimal("10000"), 3L)));
            consumer.onLoanEvent(overTheWire(LoanPaidOff.of(8L, 42L)));

            verify(notificationService).onApplicationApproved(42L, "PERSONAL_LOAN");
            verify(notificationService).onLoanDisbursed(42L, new BigDecimal("10000"), 8L);
            verify(notificationService).onLoanPaidOff(42L, 8L);
        }
    }

    @Nested
    @DisplayName("events this consumer should ignore")
    class Ignored {

        /**
         * A type added by a newer producer arrives as UnknownEvent. It must be
         * a no-op, not an exception: a deserialization or handling failure
         * repeats on the same offset and would stop the partition.
         */
        @Test
        @DisplayName("an event type this service does not know is ignored on every topic")
        void unknownType() throws Exception {
            String future = """
                    {"eventId":"abc","eventType":"SOMETHING_ADDED_LATER","eventVersion":1,
                     "occurredAt":"2026-01-01T00:00:00Z"}
                    """;
            DomainEvent event = mapper.readValue(future, DomainEvent.class);

            consumer.onAccountEvent(event);
            consumer.onTransactionEvent(event);
            consumer.onPaymentEvent(event);
            consumer.onApplicationEvent(event);
            consumer.onCreditCardEvent(event);
            consumer.onLoanEvent(event);
            consumer.onUserEvent(event);

            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("an event on the wrong topic is ignored rather than mishandled")
        void wrongTopic() throws Exception {
            // Each listener matches on type, so a record that somehow reached
            // the wrong topic does nothing instead of being read as its
            // neighbour.
            consumer.onLoanEvent(overTheWire(AccountCreated.of(3L, 42L, "CHECKING")));
            consumer.onAccountEvent(overTheWire(LoanPaidOff.of(8L, 42L)));

            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("a payment whose owner could not be resolved is skipped, not guessed")
        void unresolvedOwner() throws Exception {
            consumer.onPaymentEvent(overTheWire(PaymentCompleted.of(
                    1L, "pay-1", 3L, null, 4L, new BigDecimal("30.00"), "INTERNAL")));
            consumer.onPaymentEvent(overTheWire(PaymentFailed.of(1L, "pay-1", 3L, null)));

            verify(notificationService, Mockito.never()).onPaymentCompleted(anyLong(), any(), any());
            verify(notificationService, Mockito.never()).onPaymentFailed(eq(null));
            verifyNoInteractions(notificationService);
        }
    }
}
