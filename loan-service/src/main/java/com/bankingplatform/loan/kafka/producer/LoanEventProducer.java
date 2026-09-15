package com.bankingplatform.loan.kafka.producer;

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
public class LoanEventProducer {

    private static final String TOPIC = "loan-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishLoanDisbursed(Long loanId, Long userId, BigDecimal principal, Long accountId) {
        send(String.valueOf(loanId), Map.of(
                "eventType", "LOAN_DISBURSED",
                "loanId", loanId,
                "userId", userId,
                "principal", principal,
                "disbursementAccountId", accountId,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published LOAN_DISBURSED: loanId={}, amount={}", loanId, principal);
    }

    public void publishRepaymentMade(Long loanId, String ref, BigDecimal amount, BigDecimal remainingBalance) {
        send(ref, Map.of(
                "eventType", "LOAN_REPAYMENT_MADE",
                "loanId", loanId,
                "paymentRef", ref,
                "amount", amount,
                "remainingBalance", remainingBalance,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published LOAN_REPAYMENT_MADE: loanId={}, amount={}", loanId, amount);
    }

    public void publishLoanPaidOff(Long loanId, Long userId) {
        send(String.valueOf(loanId), Map.of(
                "eventType", "LOAN_PAID_OFF",
                "loanId", loanId,
                "userId", userId,
                "timestamp", LocalDateTime.now().toString()
        ));
        log.info("Published LOAN_PAID_OFF: loanId={}", loanId);
    }

    private void send(String key, Map<String, Object> event) {
        try {
            kafkaTemplate.send(TOPIC, key, event);
        } catch (Exception e) {
            log.warn("Kafka unavailable — event not published for key {}: {}", key, e.getMessage());
        }
    }
}
