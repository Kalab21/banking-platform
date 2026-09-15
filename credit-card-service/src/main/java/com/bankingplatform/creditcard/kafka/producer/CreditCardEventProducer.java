package com.bankingplatform.creditcard.kafka.producer;

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
public class CreditCardEventProducer {

    private static final String TOPIC = "credit-card-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishTransactionCompleted(Long cardId, String ref, String type,
                                             BigDecimal amount, BigDecimal availableCredit) {
        send(ref, Map.of(
                "eventType", "CREDIT_CARD_TRANSACTION_COMPLETED",
                "cardId", cardId,
                "transactionRef", ref,
                "transactionType", type,
                "amount", amount,
                "availableCredit", availableCredit,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published CREDIT_CARD_TRANSACTION_COMPLETED: ref={}, type={}, amount={}", ref, type, amount);
    }

    public void publishCardCreated(Long cardId, Long userId, String cardType) {
        send(String.valueOf(cardId), Map.of(
                "eventType", "CREDIT_CARD_CREATED",
                "cardId", cardId,
                "userId", userId,
                "cardType", cardType,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published CREDIT_CARD_CREATED: cardId={}, userId={}", cardId, userId);
    }

    public void publishStatementGenerated(Long cardId, Long statementId, String statementDate) {
        send(String.valueOf(cardId), Map.of(
                "eventType", "CREDIT_CARD_STATEMENT_GENERATED",
                "cardId", cardId,
                "statementId", statementId,
                "statementDate", statementDate,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published CREDIT_CARD_STATEMENT_GENERATED: cardId={}, statementId={}", cardId, statementId);
    }

    private void send(String key, Map<String, Object> event) {
        try {
            kafkaTemplate.send(TOPIC, key, event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — event not published for key {}: {}", key, e.getMessage());
        }
    }
}
