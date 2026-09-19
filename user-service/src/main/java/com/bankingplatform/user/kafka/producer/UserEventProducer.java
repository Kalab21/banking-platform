package com.bankingplatform.user.kafka.producer;

import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.events.UserLifecycleEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes changes to a customer's own status.
 *
 * <p>This producer did not exist. {@code notification-service} had listened
 * on {@code user-events} from the start, with four handlers written and
 * wired — KYC approved, KYC rejected, two-factor enabled, two-factor
 * disabled — and no service had ever published to the topic. The customer
 * notifications those handlers create had never been sent once.
 *
 * <p>Written rather than deleting the consumer because all four things
 * genuinely happen here: a staff member decides a KYC document, and a
 * customer turns a second factor on or off. The consumer was not
 * speculative; it was waiting for a producer.
 *
 * <p>The events carry a user id and nothing else. A second-factor event must
 * never carry the secret or a code, and a KYC event must never carry the
 * document or the identity number behind it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishKycApproved(Long userId) {
        send(UserLifecycleEvents.KycApproved.of(userId));
    }

    public void publishKycRejected(Long userId) {
        send(UserLifecycleEvents.KycRejected.of(userId));
    }

    public void publishTwoFactorEnabled(Long userId) {
        send(UserLifecycleEvents.TwoFactorEnabled.of(userId));
    }

    public void publishTwoFactorDisabled(Long userId) {
        send(UserLifecycleEvents.TwoFactorDisabled.of(userId));
    }

    private void send(DomainEvent event) {
        try {
            kafkaTemplate.send(Topics.USER_EVENTS, event.partitionKey(), event);
            log.info("Published {} for user {}", event.eventType(), event.partitionKey());
        } catch (Exception e) {
            log.warn("Kafka unavailable — {} not published for key {}: {}",
                    event.eventType(), event.partitionKey(), e.getMessage());
        }
    }
}
