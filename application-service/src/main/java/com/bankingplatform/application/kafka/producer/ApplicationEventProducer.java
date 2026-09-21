package com.bankingplatform.application.kafka.producer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.ApplicationRejected;
import com.bankingplatform.common.events.ApplicationSubmitted;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bankingplatform.common.kafka.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Publishes the life of an application.
 *
 * <p>The approval event is the one that causes a loan or a card to exist, so
 * the fields the issuing services read — the product type, the applicant, the
 * requested amount and the score — are part of the type rather than a map key
 * each side spells for itself.
 *
 * <p>The payload used to carry {@code applicationType} and {@code productType}
 * holding the same value, because {@code notification-service} read one and
 * the issuing services read the other. There is one name now.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationEventProducer {

    private final OutboxPublisher outbox;

    public void publishApplicationSubmitted(Long applicationId, Long userId, String productType) {
        send(ApplicationSubmitted.of(applicationId, userId, productType));
        log.info("Published APPLICATION_SUBMITTED for application {}", applicationId);
    }

    public void publishApplicationApproved(Long applicationId, Long userId, String productType,
                                           Long productId, Integer creditScore, BigDecimal requestedAmount) {
        send(ApplicationApproved.of(applicationId, userId, productType, productId, creditScore, requestedAmount));
        log.info("Published APPLICATION_APPROVED for application {}", applicationId);
    }

    public void publishApplicationRejected(Long applicationId, Long userId, String productType, String reason) {
        send(ApplicationRejected.of(applicationId, userId, productType, reason));
        log.info("Published APPLICATION_REJECTED for application {}", applicationId);
    }

    /**
     * Records the event in the outbox, in the caller's transaction.
     *
     * <p>This is the topic that causes financial products to exist: an
     * approval makes {@code loan-service} and {@code credit-card-service}
     * issue against it. A lost approval means a customer who was told yes and
     * has nothing, and nothing in the platform that knows it. Committing the
     * publication with the approval is what removes that case.
     */
    private void send(DomainEvent event) {
        outbox.publish(Topics.APPLICATION_EVENTS, event);
    }
}
