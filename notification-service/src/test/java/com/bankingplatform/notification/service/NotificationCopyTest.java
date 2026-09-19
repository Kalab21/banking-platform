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
        String message = messageAfter(() -> service.onCreditCardIssued(7L, "4111111111113823"));

        assertThat(message).contains("3823");
        assertThat(message).doesNotContain("4111111111113823");
    }
}
