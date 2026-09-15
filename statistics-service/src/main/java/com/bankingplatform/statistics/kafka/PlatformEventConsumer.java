package com.bankingplatform.statistics.kafka;

import com.bankingplatform.statistics.service.StatisticsService;
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

    private final StatisticsService statisticsService;

    @KafkaListener(topics = "account-events", groupId = "statistics-service")
    public void onAccountEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("ACCOUNT_CREATED".equals(type)) {
                statisticsService.onAccountCreated(toLong(event.get("userId")));
            }
        } catch (Exception e) {
            log.error("Error processing account-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "transaction-events", groupId = "statistics-service")
    public void onTransactionEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("TRANSACTION_CREATED".equals(type) || "TRANSFER_COMPLETED".equals(type)) {
                BigDecimal amount = toBigDecimal(event.get("amount"));
                Long userId = toLong(event.get("userId"));
                statisticsService.onTransactionCreated(userId, amount != null ? amount : BigDecimal.ZERO);
            }
        } catch (Exception e) {
            log.error("Error processing transaction-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "payment-events", groupId = "statistics-service")
    public void onPaymentEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("PAYMENT_COMPLETED".equals(type)) {
                BigDecimal amount = toBigDecimal(event.get("amount"));
                Long payerAccountId = toLong(event.get("payerAccountId"));
                statisticsService.onPaymentCompleted(payerAccountId, amount != null ? amount : BigDecimal.ZERO);
            } else if ("PAYMENT_FAILED".equals(type)) {
                statisticsService.onPaymentFailed();
            }
        } catch (Exception e) {
            log.error("Error processing payment-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "application-events", groupId = "statistics-service")
    public void onApplicationEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            switch (type != null ? type : "") {
                case "APPLICATION_SUBMITTED" -> statisticsService.onApplicationSubmitted();
                case "APPLICATION_APPROVED"  -> statisticsService.onApplicationApproved();
                case "APPLICATION_REJECTED"  -> statisticsService.onApplicationRejected();
            }
        } catch (Exception e) {
            log.error("Error processing application-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "credit-card-events", groupId = "statistics-service")
    public void onCreditCardEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("CREDIT_CARD_CREATED".equals(type)) {
                statisticsService.onCreditCardCreated(toLong(event.get("userId")));
            } else if ("CREDIT_CARD_TRANSACTION_COMPLETED".equals(type)) {
                BigDecimal amount = toBigDecimal(event.get("amount"));
                Long cardId = toLong(event.get("cardId"));
                statisticsService.onCcTransaction(cardId, amount != null ? amount : BigDecimal.ZERO);
            }
        } catch (Exception e) {
            log.error("Error processing credit-card-event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "loan-events", groupId = "statistics-service")
    public void onLoanEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("LOAN_DISBURSED".equals(type)) {
                Long userId = toLong(event.get("userId"));
                BigDecimal amount = toBigDecimal(event.get("principal"));
                statisticsService.onLoanDisbursed(userId, amount != null ? amount : BigDecimal.ZERO);
            } else if ("LOAN_REPAYMENT_MADE".equals(type)) {
                BigDecimal amount = toBigDecimal(event.get("amount"));
                statisticsService.onLoanRepayment(amount != null ? amount : BigDecimal.ZERO);
            } else if ("LOAN_PAID_OFF".equals(type)) {
                statisticsService.onLoanPaidOff(toLong(event.get("userId")));
            }
        } catch (Exception e) {
            log.error("Error processing loan-event: {}", e.getMessage());
        }
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(val.toString());
    }
}
