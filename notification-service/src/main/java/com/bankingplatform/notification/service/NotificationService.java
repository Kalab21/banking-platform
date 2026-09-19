package com.bankingplatform.notification.service;

import com.bankingplatform.notification.dto.NotificationResponse;
import com.bankingplatform.notification.dto.PagedNotificationsResponse;
import com.bankingplatform.notification.model.Notification;
import com.bankingplatform.notification.model.NotificationType;
import com.bankingplatform.notification.repository.NotificationRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private static final BigDecimal LARGE_TRANSACTION_THRESHOLD = new BigDecimal("1000");

    private final NotificationRepository repo;

    public void create(Long userId, NotificationType type, String title, String message,
                       String referenceId, String referenceType) {
        Notification n = Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .message(message)
                .referenceId(referenceId)
                .referenceType(referenceType)
                .build();
        repo.save(n);
        log.debug("Notification saved: userId={} type={}", userId, type);
    }

    public PagedNotificationsResponse getNotifications(Long userId, int page, int size) {
        Page<Notification> pg = repo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        long unread = repo.countByUserIdAndIsReadFalse(userId);
        return PagedNotificationsResponse.builder()
                .notifications(pg.getContent().stream().map(this::toResponse).toList())
                .unreadCount(unread)
                .page(page)
                .size(size)
                .totalElements(pg.getTotalElements())
                .totalPages(pg.getTotalPages())
                .build();
    }

    @Transactional
    public NotificationResponse markAsRead(Long id, Long userId) {
        int updated = repo.markAsRead(id, userId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found for this user");
        }
        return repo.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public void markAllAsRead(Long userId) {
        repo.markAllAsRead(userId);
    }

    // ---- event handlers ----

    /**
     * Money as a customer reads it.
     *
     * `$%.2f` gives "$10000.00", which is a figure nobody writes down. These
     * lines are alerts about someone's own money, so the amount is formatted
     * the way the rest of the product formats it: grouped, two decimals, US
     * locale to match the console.
     */
    private static String money(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }


    public void onAccountCreated(Long userId) {
        create(userId, NotificationType.ACCOUNT_CREATED,
                "Account Created",
                "Your bank account has been successfully created. Welcome!",
                null, null);
    }

    public void onLargeTransaction(Long userId, BigDecimal amount, String txRef) {
        if (amount.compareTo(LARGE_TRANSACTION_THRESHOLD) > 0) {
            create(userId, NotificationType.LARGE_TRANSACTION_ALERT,
                    "Large Transaction Alert",
                    String.format("A transaction of %s was made on your account. If you did not initiate this, contact support immediately.", money(amount)),
                    txRef, "TRANSACTION");
        }
    }

    public void onOverdraft(Long userId, BigDecimal amount) {
        create(userId, NotificationType.OVERDRAFT_ALERT,
                "Overdraft Alert",
                String.format("Your account balance has gone below zero after a transaction of %s.", money(amount)),
                null, null);
    }

    public void onPaymentCompleted(Long userId, BigDecimal amount, String paymentRef) {
        create(userId, NotificationType.PAYMENT_RECEIPT,
                "Payment Receipt",
                String.format("Payment of %s was completed successfully.", money(amount)),
                paymentRef, "PAYMENT");
    }

    public void onPaymentFailed(Long userId) {
        create(userId, NotificationType.PAYMENT_FAILED,
                "Payment Failed",
                "Your payment could not be processed. Please check your account balance and try again.",
                null, null);
    }

    public void onApplicationApproved(Long userId, String productType) {
        create(userId, NotificationType.APPLICATION_APPROVED,
                "Application Approved",
                String.format("Congratulations! Your %s application has been approved.", productType),
                null, "APPLICATION");
    }

    public void onApplicationRejected(Long userId, String productType) {
        create(userId, NotificationType.APPLICATION_REJECTED,
                "Application Update",
                String.format("We're sorry, your %s application was not approved at this time.", productType),
                null, "APPLICATION");
    }

    /**
     * @param last4 the last four digits of the card, or null if unknown
     *
     * <p>Takes four digits rather than a card number. It used to take the
     * whole number and call {@code substring(length - 4)} on it — and the
     * producer never sent one, so every issuance notification threw
     * {@link StringIndexOutOfBoundsException} on the empty default and was
     * swallowed by the consumer's catch block. A notification does not need
     * a PAN to name a card, and taking one would put it on a Kafka topic.
     */
    public void onCreditCardIssued(Long userId, String last4) {
        String masked = last4 == null || last4.isBlank()
                ? "your new card"
                : "**** **** **** " + last4;
        create(userId, NotificationType.CREDIT_CARD_ISSUED,
                "Credit Card Issued",
                String.format("Your new credit card %s has been issued and is ready to use.", masked),
                null, "CREDIT_CARD");
    }

    public void onCreditCardStatementGenerated(Long userId, String statementDate) {
        create(userId, NotificationType.CREDIT_CARD_STATEMENT_AVAILABLE,
                "Statement Available",
                String.format("Your credit card statement for %s is now available.", statementDate),
                statementDate, "CREDIT_CARD_STATEMENT");
    }

    public void onLoanDisbursed(Long userId, BigDecimal amount, Long loanId) {
        create(userId, NotificationType.LOAN_DISBURSED,
                "Loan Disbursed",
                String.format("Your loan of %s has been disbursed to your account.", money(amount)),
                loanId != null ? loanId.toString() : null, "LOAN");
    }

    public void onLoanPaymentDue(Long userId, Long loanId, String dueDate) {
        create(userId, NotificationType.LOAN_PAYMENT_DUE,
                "Loan Payment Due Soon",
                String.format("Your loan payment is due on %s. Please ensure sufficient funds in your account.", dueDate),
                loanId != null ? loanId.toString() : null, "LOAN");
    }

    public void onLoanLate(Long userId, Long loanId) {
        create(userId, NotificationType.LOAN_LATE_NOTICE,
                "Loan Payment Overdue",
                "Your loan payment is overdue. Late fees may apply. Please make a payment as soon as possible.",
                loanId != null ? loanId.toString() : null, "LOAN");
    }

    public void onLoanPaidOff(Long userId, Long loanId) {
        create(userId, NotificationType.LOAN_PAID_OFF,
                "Loan Paid Off - Congratulations!",
                "Congratulations! You have fully paid off your loan. Thank you for banking with us.",
                loanId != null ? loanId.toString() : null, "LOAN");
    }

    public void onKycApproved(Long userId) {
        create(userId, NotificationType.KYC_APPROVED,
                "KYC Verification Approved",
                "Your identity verification has been approved. You now have full access to all platform features.",
                null, null);
    }

    public void onKycRejected(Long userId) {
        create(userId, NotificationType.KYC_REJECTED,
                "KYC Verification Update",
                "Your identity verification could not be completed. Please contact support with valid identification documents.",
                null, null);
    }

    public void onTwoFaEnabled(Long userId) {
        create(userId, NotificationType.TWO_FA_ENABLED,
                "Two-Factor Authentication Enabled",
                "Two-factor authentication has been enabled on your account. Your account is now more secure.",
                null, null);
    }

    public void onTwoFaDisabled(Long userId) {
        create(userId, NotificationType.TWO_FA_DISABLED,
                "Two-Factor Authentication Disabled",
                "Two-factor authentication has been disabled on your account. We recommend re-enabling it for added security.",
                null, null);
    }

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .userId(n.getUserId())
                .type(n.getType())
                .title(n.getTitle())
                .message(n.getMessage())
                .isRead(n.isRead())
                .referenceId(n.getReferenceId())
                .referenceType(n.getReferenceType())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
