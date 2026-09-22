package com.bankingplatform.application.kafka.consumer;

import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationTransitions;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.common.events.CreditCardCreated;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.LoanCreated;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The confirmation that a product exists, which is the only thing that may move
 * an application to {@code PROVISIONED}.
 *
 * <p>Until this existed, an approved credit application sat at
 * {@code PROVISIONING} for ever, because nothing downstream ever answered. The
 * honest alternative — which an earlier version of this service chose — was to
 * write {@code PROVISIONED} the moment the request was published, which claimed
 * a product existed on the strength of a message having been sent. It also
 * stored {@code product_id = -1}, because there was no real id to store.
 *
 * <p>So the application now records the product's real id, from the service
 * that created it, and only then says the product exists. A downstream service
 * that is slow, restarting or broken leaves the application truthfully at
 * {@code PROVISIONING} until it answers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductCreatedConsumer {

    private static final String CARD_CONSUMER = "application-service:card-created";
    private static final String LOAN_CONSUMER = "application-service:loan-created";

    private final ApplicationRepository applicationRepository;
    private final ProcessedEventGuard processedEvents;

    @KafkaListener(topics = Topics.CREDIT_CARD_EVENTS, groupId = "application-service")
    @Transactional
    public void onCardEvent(DomainEvent event) {
        if (!(event instanceof CreditCardCreated created)) {
            return;
        }
        confirm(CARD_CONSUMER, created.eventId(), created.applicationId(), created.cardId(), "card");
    }

    @KafkaListener(topics = Topics.LOAN_EVENTS, groupId = "application-service")
    @Transactional
    public void onLoanEvent(DomainEvent event) {
        if (!(event instanceof LoanCreated created)) {
            return;
        }
        confirm(LOAN_CONSUMER, created.eventId(), created.applicationId(), created.loanId(), "loan");
    }

    /**
     * Records the real product id and completes provisioning.
     *
     * <p>Nothing here throws on a product that has no application behind it.
     * A card or loan created by some other route is a legitimate thing for this
     * service not to care about, and dead-lettering it would turn indifference
     * into an incident.
     */
    private void confirm(String consumer, String eventId, Long applicationId, Long productId,
                         String what) {
        if (applicationId == null) {
            // An older payload, or a product created outside the application
            // lifecycle. Nothing to confirm.
            return;
        }

        Optional<Application> found = applicationRepository.findById(applicationId);
        if (found.isEmpty()) {
            log.warn("A {} named application {}, which does not exist; ignoring", what, applicationId);
            return;
        }
        Application application = found.get();

        // Claimed in the same transaction as the update, so a redelivery cannot
        // move an application that has already been confirmed.
        if (!processedEvents.claim(consumer, eventId)) {
            log.info("Already confirmed {} for application {}; ignoring redelivery",
                    what, applicationId);
            return;
        }

        if (application.getStatus() == ApplicationStatus.PROVISIONED) {
            // Confirmed by an earlier delivery whose claim is no longer
            // visible. The product id is already recorded; nothing to do.
            log.info("Application {} is already provisioned; ignoring {} confirmation",
                    applicationId, what);
            return;
        }

        if (application.getStatus() != ApplicationStatus.PROVISIONING) {
            // A confirmation for an application that never asked, or that was
            // cancelled while the product was being created. Recording the id
            // would be the wrong kind of tidy: the lifecycle says this move is
            // not legal, and the log is where a human should find out.
            log.warn("A {} was created for application {}, which is {} rather than PROVISIONING;"
                            + " not recording it", what, applicationId, application.getStatus());
            return;
        }

        application.setProductId(productId);
        ApplicationTransitions.assertCanMove(application.getApplicationType(),
                application.getStatus(), ApplicationStatus.PROVISIONED);
        application.setStatus(ApplicationStatus.PROVISIONED);
        applicationRepository.save(application);

        log.info("Application {} provisioned: {} id={}", applicationId, what, productId);
    }
}
