package com.bankingplatform.fraud.kafka;

import com.bankingplatform.fraud.service.FraudDetectionService;
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

    private final FraudDetectionService fraudService;

    @KafkaListener(topics = "transaction-events", groupId = "fraud-detection-service")
    public void onTransactionEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("TRANSACTION_CREATED".equals(type) || "TRANSFER_COMPLETED".equals(type)) {
                Long accountId = toLong(event.get("accountId"));
                Long userId = toLong(event.get("userId"));
                BigDecimal amount = toBigDecimal(event.get("amount"));
                String txRef = toStr(event.get("transactionRef"));
                if (accountId != null) {
                    fraudService.evaluateTransaction(accountId, userId, amount, txRef);
                }
            }
        } catch (Exception e) {
            log.error("Error processing transaction-event for fraud: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "credit-card-events", groupId = "fraud-detection-service")
    public void onCreditCardEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("CREDIT_CARD_TRANSACTION_COMPLETED".equals(type)) {
                Long accountId = toLong(event.get("accountId"));
                Long userId = toLong(event.get("userId"));
                BigDecimal amount = toBigDecimal(event.get("amount"));
                String txRef = toStr(event.get("transactionRef"));
                if (accountId != null) {
                    fraudService.evaluateCreditCardPurchase(accountId, userId, amount, txRef);
                }
            }
        } catch (Exception e) {
            log.error("Error processing credit-card-event for fraud: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "payment-events", groupId = "fraud-detection-service")
    public void onPaymentEvent(Map<String, Object> event) {
        try {
            String type = (String) event.get("eventType");
            if ("PAYMENT_FAILED".equals(type)) {
                Long accountId = toLong(event.get("payerAccountId"));
                Long userId = toLong(event.get("userId"));
                if (accountId != null) {
                    fraudService.evaluateFailedPayment(accountId, userId);
                }
            }
        } catch (Exception e) {
            log.error("Error processing payment-event for fraud: {}", e.getMessage());
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

    private String toStr(Object val) {
        return val != null ? val.toString() : null;
    }
}
