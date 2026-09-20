package com.bankingplatform.notification.service;

import com.bankingplatform.notification.model.Notification;
import com.bankingplatform.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Alerts are read by customers, so they are written for customers.
 *
 * The amounts in these messages used to be produced by {@code $%.2f}, which
 * turns ten thousand dollars into "$10000.00" — a figure no bank statement,
 * and no person, writes that way. The notifications say the same thing the
 * console says, in the same shape.
 */
@ExtendWith(MockitoExtension.class)
class NotificationCopyTest {

    @Mock private NotificationRepository repo;

    private String messageAfter(Runnable event) {
        event.run();
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repo).save(saved.capture());
        return saved.getValue().getMessage();
    }

    private NotificationService service() {
        return new NotificationService(repo);
    }

    @Test
    @DisplayName("a disbursed loan is announced in grouped dollars")
    void loanDisbursed() {
        NotificationService service = service();
        String message = messageAfter(() -> service.onLoanDisbursed(7L, new BigDecimal("10000.00"), 3L));

        assertThat(message).contains("$10,000.00");
        assertThat(message).doesNotContain("$10000.00");
    }

    @Test
    @DisplayName("a large transaction alert is too")
    void largeTransaction() {
        NotificationService service = service();
        String message = messageAfter(() -> service.onLargeTransaction(7L, new BigDecimal("2500.50"), "ref"));

        assertThat(message).contains("$2,500.50");
    }

    @Test
    @DisplayName("so is a payment receipt")
    void paymentReceipt() {
        NotificationService service = service();
        String message = messageAfter(() -> service.onPaymentCompleted(7L, new BigDecimal("1200"), "ref"));

        assertThat(message).contains("$1,200.00");
    }

    @Test
    @DisplayName("and an overdraft alert, which is the one a customer reads closely")
    void overdraft() {
        NotificationService service = service();
        String message = messageAfter(() -> service.onOverdraft(7L, new BigDecimal("42.5")));

        assertThat(message).contains("$42.50");
    }

    @Test
    @DisplayName("a card is named by its last four digits and nothing more")
    void cardIssued() {
        NotificationService service = service();
        // The caller supplies four digits now, not a card number. The full
        // number never reaches this service: credit-card-service masks it
        // before it publishes, and the event has no field to carry one.
        String message = messageAfter(() -> service.onCreditCardIssued(7L, "3823"));

        assertThat(message).contains("3823");
        assertThat(message).doesNotContain("4111111111113823");
    }

    @Test
    @DisplayName("a declined application names the product the way a customer says it")
    void rejectionCopyIsReadable() {
        // The product type reaches these messages for the first time now that
        // the event carries it. Printed raw, a declined mortgage read "your
        // MORTGAGE application was not approved".
        NotificationService service = service();
        String message = messageAfter(() -> service.onApplicationRejected(7L, "MORTGAGE"));

        assertThat(message).contains("mortgage").doesNotContain("MORTGAGE");
    }

    @Test
    @DisplayName("an approved application names the product the same way")
    void approvalCopyIsReadable() {
        NotificationService service = service();
        String message = messageAfter(() -> service.onApplicationApproved(7L, "PERSONAL_LOAN"));

        assertThat(message).contains("personal loan").doesNotContain("PERSONAL_LOAN");
    }

    @Test
    @DisplayName("an application decision with no product type still reads as a sentence")
    void applicationCopyWithoutProduct() {
        NotificationService service = service();

        assertThat(messageAfter(() -> service.onApplicationRejected(7L, null)))
                .contains("your product application");
    }

    @Test
    @DisplayName("a card with no digits still produces a sentence rather than an error")
    void cardIssuedWithoutDigits() {
        NotificationService service = service();

        // This used to be a crash, not a fallback. The method took a whole
        // card number and called substring(length - 4) on it, and the
        // producer never sent one — so every issuance notification threw
        // StringIndexOutOfBoundsException on the empty default and the
        // consumer's catch block swallowed it.
        String message = messageAfter(() -> service.onCreditCardIssued(7L, null));

        assertThat(message).contains("your new card");
    }
}
