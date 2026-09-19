package com.bankingplatform.user.kafka.consumer;

import com.bankingplatform.common.events.CreditCardTransactionCompleted;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.user.service.CreditScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Raises a credit score when a card is paid down.
 *
 * <p>The listener took a {@code String} and parsed the JSON itself with an
 * {@code ObjectMapper}, which is why it reinvented the field names. It reads
 * the shared event type now, like every other consumer on the platform.
 *
 * <p>The reward had never been given: the card event carried no
 * {@code userId} and this returned as soon as it read null.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CreditCardEventConsumer {

    private final CreditScoreService creditScoreService;

    @KafkaListener(topics = Topics.CREDIT_CARD_EVENTS, groupId = "user-service")
    public void consume(DomainEvent event) {
        if (event instanceof CreditCardTransactionCompleted e
                && "PAYMENT".equals(e.transactionType())
                && e.userId() != null) {
            creditScoreService.updateScore(e.userId(), +5, "Credit card payment made");
        }
    }
}
