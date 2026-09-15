package com.bankingplatform.payment.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    private static final String TOPIC = "payment-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishPaymentCompleted(Long paymentId, String ref, Long payerAccountId,
                                        Long payeeAccountId, BigDecimal amount, String type) {
        send(ref, Map.of(
                "eventType", "PAYMENT_COMPLETED",
                "paymentId", paymentId,
                "paymentRef", ref,
                "payerAccountId", payerAccountId,
                "payeeAccountId", payeeAccountId != null ? payeeAccountId : "",
                "amount", amount,
                "paymentType", type,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published PAYMENT_COMPLETED: ref={}, amount={}", ref, amount);
    }

    public void publishPaymentFailed(Long paymentId, String ref, String reason) {
        send(ref, Map.of(
                "eventType", "PAYMENT_FAILED",
                "paymentId", paymentId,
                "paymentRef", ref,
                "reason", reason,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.warn("Published PAYMENT_FAILED: ref={}, reason={}", ref, reason);
    }

    private void send(String key, Map<String, Object> event) {
        try {
            kafkaTemplate.send(TOPIC, key, event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — event not published for key {}: {}", key, e.getMessage());
        }
    }
}
