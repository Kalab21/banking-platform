package com.bankingplatform.notification.kafka;

import com.bankingplatform.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = "account-events", groupId = "notification-service")
    public void onAccountEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("ACCOUNT_CREATED".equals(type)) {
                Long userId = toLong(event.get("userId"));
                if (userId != null) notificationService.onAccountCreated(userId);
            }
        } catch (Exception e) {
            log.error("Error processing account-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "transaction-events", groupId = "notification-service")
    public void onTransactionEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("TRANSACTION_CREATED".equals(type) || "TRANSFER_COMPLETED".equals(type)) {
                Long userId = toLong(event.get("userId"));
                BigDecimal amount = toBigDecimal(event.get("amount"));
                String txRef = toString(event.get("transactionRef"));
                if (userId != null && amount != null) {
                    notificationService.onLargeTransaction(userId, amount, txRef);
                }
            } else if ("OVERDRAFT_TRIGGERED".equals(type)) {
                Long userId = toLong(event.get("userId"));
                BigDecimal amount = toBigDecimal(event.get("amount"));
                if (userId != null) {
                    notificationService.onOverdraft(userId, amount != null ? amount : BigDecimal.ZERO);
                }
            }
        } catch (Exception e) {
            log.error("Error processing transaction-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "payment-events", groupId = "notification-service")
    public void onPaymentEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("PAYMENT_COMPLETED".equals(type)) {
                Long userId = toLong(event.get("userId"));
                BigDecimal amount = toBigDecimal(event.get("amount"));
                String paymentRef = toString(event.get("paymentRef"));
                if (userId != null) {
                    notificationService.onPaymentCompleted(userId, amount != null ? amount : BigDecimal.ZERO, paymentRef);
                }
            } else if ("PAYMENT_FAILED".equals(type)) {
                Long userId = toLong(event.get("userId"));
                if (userId != null) notificationService.onPaymentFailed(userId);
            }
        } catch (Exception e) {
            log.error("Error processing payment-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "application-events", groupId = "notification-service")
    public void onApplicationEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            Long userId = toLong(event.get("userId"));
            String productType = toString(event.get("productType"));
            if (userId == null) return;
            if ("APPLICATION_APPROVED".equals(type)) {
                notificationService.onApplicationApproved(userId, productType != null ? productType : "product");
            } else if ("APPLICATION_REJECTED".equals(type)) {
                notificationService.onApplicationRejected(userId, productType != null ? productType : "product");
            }
        } catch (Exception e) {
            log.error("Error processing application-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "credit-card-events", groupId = "notification-service")
    public void onCreditCardEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            Long userId = toLong(event.get("userId"));
            if (userId == null) return;
            if ("CREDIT_CARD_CREATED".equals(type)) {
                String cardNumber = toString(event.get("cardNumber"));
                notificationService.onCreditCardIssued(userId, cardNumber != null ? cardNumber : "");
            } else if ("STATEMENT_GENERATED".equals(type)) {
                String statementDate = toString(event.get("statementDate"));
                notificationService.onCreditCardStatementGenerated(userId, statementDate != null ? statementDate : "");
            }
        } catch (Exception e) {
            log.error("Error processing credit-card-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "loan-events", groupId = "notification-service")
    public void onLoanEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            Long userId = toLong(event.get("userId"));
            Long loanId = toLong(event.get("loanId"));
            if (userId == null) return;
            switch (type != null ? type : "") {
                case "LOAN_DISBURSED" -> {
                    BigDecimal amount = toBigDecimal(event.get("principal"));
                    notificationService.onLoanDisbursed(userId, amount != null ? amount : BigDecimal.ZERO, loanId);
                }
                case "LOAN_PAYMENT_DUE" -> {
                    String dueDate = toString(event.get("dueDate"));
                    notificationService.onLoanPaymentDue(userId, loanId, dueDate != null ? dueDate : "");
                }
                case "LOAN_PAYMENT_MISSED" -> notificationService.onLoanLate(userId, loanId);
                case "LOAN_PAID_OFF" -> notificationService.onLoanPaidOff(userId, loanId);
            }
        } catch (Exception e) {
            log.error("Error processing loan-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "user-events", groupId = "notification-service")
    public void onUserEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            Long userId = toLong(event.get("userId"));
            if (userId == null) return;
            switch (type != null ? type : "") {
                case "KYC_APPROVED"   -> notificationService.onKycApproved(userId);
                case "KYC_REJECTED"   -> notificationService.onKycRejected(userId);
                case "TWO_FA_ENABLED" -> notificationService.onTwoFaEnabled(userId);
                case "TWO_FA_DISABLED" -> notificationService.onTwoFaDisabled(userId);
            }
        } catch (Exception e) {
            log.error("Error processing user-event: {}", e.getMessage());
        }
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private String toString(Object val) {
        return val != null ? val.toString() : null;
    }
}
