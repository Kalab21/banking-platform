package com.bankingplatform.application.service.impl;

import com.bankingplatform.application.dto.OfferResponse;
import com.bankingplatform.application.exception.OfferException;
import com.bankingplatform.application.exception.ResourceNotFoundException;
import com.bankingplatform.application.kafka.producer.ApplicationEventProducer;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationTransitions;
import com.bankingplatform.application.model.Offer;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.application.repository.OfferRepository;
import com.bankingplatform.application.service.OfferService;
import com.bankingplatform.application.underwriting.OfferedTerms;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Making an offer, and what the customer may do with it.
 *
 * <p>An offer is the contract between the decision and the product. Its terms
 * are written once and never edited, so the product that results carries the
 * figures the customer actually agreed to — not the figures they asked for, and
 * not figures a downstream service worked out for itself.
 *
 * <p>Acceptance is the only thing that starts provisioning. Everything that
 * could go wrong with it is refused rather than tolerated: accepting twice,
 * accepting after declining, accepting someone else's offer, accepting one that
 * has expired. Two requests racing each other are settled by the row's version
 * — the loser is told the offer moved, rather than both winning and two
 * products being created.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OfferServiceImpl implements OfferService {

    /** Long enough for a customer to think about it; short enough to mean something. */
    private static final int OFFER_VALID_DAYS = 30;

    private final OfferRepository offerRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationEventProducer eventProducer;

    @Override
    @Transactional
    public Offer offer(Application application, OfferedTerms terms, Long decisionSnapshotId) {
        if (terms == null) {
            throw new OfferException(
                    "An approved credit application must be priced before it can be offered");
        }
        LocalDateTime now = LocalDateTime.now();
        return offerRepository.save(Offer.builder()
                .applicationId(application.getId())
                .decisionSnapshotId(decisionSnapshotId)
                .userId(application.getUserId())
                .productType(application.getApplicationType())
                .status(Offer.Status.OFFERED)
                .approvedAmount(application.getApprovedAmount())
                .apr(terms.apr())
                .termMonths(terms.termMonths())
                .monthlyPayment(terms.monthlyPayment())
                .creditLimit(terms.creditLimit())
                .cardTier(terms.cardTier())
                .currency(application.getCurrency() != null ? application.getCurrency() : "USD")
                .expiresAt(now.plusDays(OFFER_VALID_DAYS))
                .build());
    }

    @Override
    @Transactional
    public OfferResponse accept(Long applicationId, Long callerUserId) {
        Offer offer = liveOffer(applicationId, callerUserId);

        // Already accepted: say so rather than provisioning a second time. A
        // customer who double-clicks has not asked for two loans.
        if (offer.getStatus() == Offer.Status.ACCEPTED) {
            return OfferResponse.from(offer);
        }
        refuseIfClosed(offer);

        LocalDateTime now = LocalDateTime.now();
        if (offer.hasExpired(now)) {
            offer.expire();
            offerRepository.save(offer);
            throw new OfferException("This offer has expired");
        }

        offer.accept(now);
        offerRepository.save(offer);

        Application application = application(applicationId);
        move(application, ApplicationStatus.ACCEPTED);
        move(application, ApplicationStatus.PROVISIONING);
        Application saved = applicationRepository.save(application);

        // Only now does anything downstream hear about it, and it hears the
        // accepted terms rather than a credit score to re-derive them from.
        eventProducer.publishApplicationApproved(saved.getId(), saved.getUserId(),
                saved.getApplicationType().name(), null,
                saved.getCreditScoreAtApply(), saved.getRequestedAmount(),
                offer.getApprovedAmount(),
                offer.getApr(), offer.getTermMonths(),
                offer.getCreditLimit(), offer.getCardTier());

        log.info("Offer {} accepted for application {}; provisioning requested",
                offer.getId(), applicationId);
        return OfferResponse.from(offer);
    }

    @Override
    @Transactional
    public OfferResponse decline(Long applicationId, Long callerUserId) {
        Offer offer = liveOffer(applicationId, callerUserId);

        if (offer.getStatus() == Offer.Status.DECLINED) {
            return OfferResponse.from(offer);
        }
        refuseIfClosed(offer);

        offer.decline(LocalDateTime.now());
        offerRepository.save(offer);

        Application application = application(applicationId);
        move(application, ApplicationStatus.DECLINED);
        applicationRepository.save(application);

        log.info("Offer {} declined for application {}", offer.getId(), applicationId);
        return OfferResponse.from(offer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OfferResponse> forApplication(Long applicationId) {
        return offerRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).stream()
                .map(OfferResponse::from)
                .toList();
    }

    /**
     * The most recent offer on this application, and only if it belongs to the
     * caller. Ownership comes from the stored offer, never from the request:
     * an application id in a path says which offer is wanted, not who may act
     * on it.
     */
    private Offer liveOffer(Long applicationId, Long callerUserId) {
        Offer offer = offerRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No offer exists for application " + applicationId));
        if (callerUserId != null && !callerUserId.equals(offer.getUserId())) {
            throw new ResourceNotFoundException(
                    "No offer exists for application " + applicationId);
        }
        return offer;
    }

    /** A declined or expired offer is finished, and cannot be revived by accepting it. */
    private void refuseIfClosed(Offer offer) {
        if (!offer.isOpen()) {
            throw new OfferException("This offer is " + offer.getStatus().name().toLowerCase()
                    + " and can no longer be acted on");
        }
    }

    private Application application(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));
    }

    private void move(Application application, ApplicationStatus to) {
        ApplicationTransitions.assertCanMove(
                application.getApplicationType(), application.getStatus(), to);
        application.setStatus(to);
    }
}
