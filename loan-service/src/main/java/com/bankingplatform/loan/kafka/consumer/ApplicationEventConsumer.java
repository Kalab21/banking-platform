package com.bankingplatform.loan.kafka.consumer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.Topics;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.model.LoanType;
import com.bankingplatform.loan.service.LoanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Creates the loan behind an approved application.
 *
 * <p>Reads the shared {@link ApplicationApproved} type rather than pulling
 * {@code productType}, {@code requestedAmount} and {@code creditScore} out of
 * a map by name. The approval used to carry both {@code applicationType} and
 * {@code productType} holding the same value, because this consumer read one
 * and notification-service read the other.
 *
 * <p>This handler issues a financial product, so a redelivery of the same
 * event would issue a second loan. It claims the event id in the same
 * transaction that creates the loan: both happen or neither does, and a
 * second delivery finds the claim taken and does nothing.
 *
 * <p>A partial unique index on {@code loans.application_id} backs that up, so
 * two loans for one approved application cannot exist even if a duplicate
 * arrives by some route that misses this guard.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationEventConsumer {

    private static final Set<String> LOAN_PRODUCT_TYPES = Set.of("PERSONAL_LOAN", "AUTO_LOAN", "MORTGAGE");
    /** Identifies this handler in the processed-event table. */
    private static final String CONSUMER = "loan-service:application-approved";

    private final LoanService loanService;
    private final ProcessedEventGuard processedEvents;

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "loan-service")
    @Transactional
    public void onApplicationEvent(DomainEvent event) {
        // The null check comes first: Set.of(...) is an immutable set, and its
        // contains() throws NullPointerException on a null argument rather
        // than returning false. An approval that arrives without a
        // productType would have become a poison record on this partition.
        // The credit-card consumer uses CREDIT_CARD.equals(...), which is
        // null-safe; this is the same behaviour, stated explicitly.
        if (!(event instanceof ApplicationApproved approved)
                || approved.productType() == null
                || !LOAN_PRODUCT_TYPES.contains(approved.productType())) {
            return;
        }

        if (approved.userId() == null) {
            log.warn("Ignoring an approved application with no applicant: applicationId={}",
                    approved.applicationId());
            return;
        }

        // Claimed in the same transaction as the loan it creates. Kafka is
        // at-least-once and this service now retries, so a redelivery is
        // ordinary — and this handler's side effect is issuing a loan.
        if (!processedEvents.claim(CONSUMER, approved.eventId())) {
            log.info("Already issued a loan for event {} (applicationId={}); ignoring redelivery",
                    approved.eventId(), approved.applicationId());
            return;
        }

        CreateLoanRequest request = new CreateLoanRequest();
        request.setUserId(approved.userId());
        request.setApplicationId(approved.applicationId());
        request.setLoanType(LoanType.valueOf(approved.productType()));
        request.setPrincipal(approved.requestedAmount() != null
                ? approved.requestedAmount()
                : resolveDefaultPrincipal(approved.productType()));
        request.setInterestRate(resolveRate(approved.productType(), approved.creditScore()));
        request.setTermMonths(resolveTermMonths(approved.productType()));

        loanService.createLoan(request);
        log.info("Created loan for userId={}, applicationId={}, type={}",
                approved.userId(), approved.applicationId(), approved.productType());
    }

    private BigDecimal resolveDefaultPrincipal(String loanType) {
        return switch (loanType) {
            case "MORTGAGE" -> new BigDecimal("200000");
            case "AUTO_LOAN" -> new BigDecimal("25000");
            default -> new BigDecimal("10000");
        };
    }

    private BigDecimal resolveRate(String loanType, Integer creditScore) {
        int score = creditScore != null ? creditScore : 650;
        return switch (loanType) {
            case "MORTGAGE" -> score >= 760 ? new BigDecimal("6.50") : new BigDecimal("7.25");
            case "AUTO_LOAN" -> score >= 720 ? new BigDecimal("7.99") : new BigDecimal("9.99");
            default -> score >= 720 ? new BigDecimal("10.99") : new BigDecimal("14.99");
        };
    }

    private Integer resolveTermMonths(String loanType) {
        return switch (loanType) {
            case "MORTGAGE" -> 360;
            case "AUTO_LOAN" -> 60;
            default -> 48;
        };
    }



}
