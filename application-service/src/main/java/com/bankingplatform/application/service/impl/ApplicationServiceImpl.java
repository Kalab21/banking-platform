package com.bankingplatform.application.service.impl;

import com.bankingplatform.common.security.CallerContext;
import com.bankingplatform.application.client.AccountClient;
import com.bankingplatform.application.client.UserClient;
import com.bankingplatform.application.dto.*;
import com.bankingplatform.application.exception.ApplicationException;
import com.bankingplatform.application.exception.ResourceNotFoundException;
import com.bankingplatform.application.kafka.producer.ApplicationEventProducer;
import com.bankingplatform.application.mapper.ApplicationMapper;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.model.AuditLog;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.application.repository.AuditLogRepository;
import com.bankingplatform.application.service.ApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationServiceImpl implements ApplicationService {

    // Minimum credit scores per product type
    private static final Map<ApplicationType, Integer> MIN_CREDIT_SCORES = Map.of(
            ApplicationType.CHECKING_ACCOUNT, 0,
            ApplicationType.SAVINGS_ACCOUNT, 0,
            ApplicationType.CREDIT_CARD, 650,
            ApplicationType.PERSONAL_LOAN, 600,
            ApplicationType.AUTO_LOAN, 620,
            ApplicationType.MORTGAGE, 700
    );

    private final ApplicationRepository applicationRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationMapper applicationMapper;
    private final ApplicationEventProducer eventProducer;
    private final UserClient userClient;
    private final AccountClient accountClient;

    @Override
    @Transactional
    public ApplicationResponse submitApplication(CreateApplicationRequest request) {
        UserResponse user = userClient.getUserById(request.getUserId());

        if (!user.isEnabled()) {
            throw new ApplicationException("User account is disabled");
        }
        if ("REJECTED".equals(user.getKycStatus())) {
            throw new ApplicationException("KYC verification rejected — cannot submit application");
        }

        int creditScore = user.getCreditScore() != null ? user.getCreditScore() : 0;
        int minScore = MIN_CREDIT_SCORES.getOrDefault(request.getApplicationType(), 650);

        Application application = Application.builder()
                .userId(request.getUserId())
                .applicationType(request.getApplicationType())
                .requestedAmount(request.getRequestedAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .termMonths(request.getTermMonths())
                .purpose(request.getPurpose())
                .creditScoreAtApply(creditScore)
                .build();

        // Every application is submitted before it is decided, and
        // statistics-service counts submissions — but nothing had ever called
        // publishApplicationSubmitted, so that counter had always read zero.
        //
        // Saved first so the event can name an id that exists, then published
        // inside this transaction rather than after it. The outbox is a
        // database row, so it rolls back with the application: if
        // provisionProduct below fails, the row and the publication disappear
        // together. The after-commit hook this used to need was working around
        // an unreliable send, and is the wrong tool once the send is a write.
        Application submitted = applicationRepository.save(application);
        eventProducer.publishApplicationSubmitted(
                submitted.getId(), request.getUserId(), request.getApplicationType().name());

        // Auto-approve or reject based on credit score
        if (creditScore >= minScore) {
            application.setStatus(ApplicationStatus.APPROVED);
            application.setApprovedAmount(request.getRequestedAmount());
            application.setReviewedAt(LocalDateTime.now());
            application.setReviewerNotes("Auto-approved: credit score " + creditScore + " meets minimum " + minScore);

            Application saved = applicationRepository.save(application);
            Long productId = provisionProduct(saved, request.getCurrency());
            saved.setProductId(productId);
            saved.setStatus(ApplicationStatus.DISBURSED);
            applicationRepository.save(saved);

            audit("APPLICATION", saved.getId(), "AUTO_APPROVED",
                    "Type: " + request.getApplicationType() + ", ProductId: " + productId);
            eventProducer.publishApplicationApproved(saved.getId(), request.getUserId(),
                    request.getApplicationType().name(), productId,
                    creditScore, request.getRequestedAmount());

            return applicationMapper.toResponse(saved);
        } else {
            application.setStatus(ApplicationStatus.REJECTED);
            application.setReviewedAt(LocalDateTime.now());
            application.setReviewerNotes("Auto-rejected: credit score " + creditScore + " below minimum " + minScore);

            Application saved = applicationRepository.save(application);
            audit("APPLICATION", saved.getId(), "AUTO_REJECTED",
                    "Type: " + request.getApplicationType() + ", Score: " + creditScore);
            eventProducer.publishApplicationRejected(saved.getId(), request.getUserId(),
                    request.getApplicationType().name(), "Credit score below minimum");

            return applicationMapper.toResponse(saved);
        }
    }

    @Override
    public ApplicationResponse getById(Long id) {
        return applicationMapper.toResponse(findById(id));
    }

    @Override
    public List<ApplicationResponse> getByUserId(Long userId) {
        return applicationRepository.findByUserId(userId).stream()
                .map(applicationMapper::toResponse)
                .toList();
    }

    @Override
    public List<ApplicationResponse> getByStatus(ApplicationStatus status) {
        return applicationRepository.findByStatus(status).stream()
                .map(applicationMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public ApplicationResponse review(Long id, ReviewRequest request) {
        Application application = findById(id);

        if (application.getStatus() == ApplicationStatus.DISBURSED
                || application.getStatus() == ApplicationStatus.CANCELLED) {
            throw new ApplicationException("Cannot review application in status: " + application.getStatus());
        }

        application.setStatus(request.getStatus());
        application.setReviewedAt(LocalDateTime.now());
        if (request.getReviewerNotes() != null) {
            application.setReviewerNotes(request.getReviewerNotes());
        }

        if (request.getStatus() == ApplicationStatus.APPROVED) {
            application.setApprovedAmount(
                    request.getApprovedAmount() != null ? request.getApprovedAmount() : application.getRequestedAmount());
            Long productId = provisionProduct(application, application.getCurrency());
            application.setProductId(productId);
            application.setStatus(ApplicationStatus.DISBURSED);

            audit("APPLICATION", id, "MANUAL_APPROVED",
                    "ProductId: " + productId);
            eventProducer.publishApplicationApproved(id, application.getUserId(),
                    application.getApplicationType().name(), productId,
                    application.getCreditScoreAtApply(), application.getRequestedAmount());
        } else if (request.getStatus() == ApplicationStatus.REJECTED) {
            audit("APPLICATION", id, "MANUAL_REJECTED", request.getReviewerNotes());
            eventProducer.publishApplicationRejected(id, application.getUserId(),
                    application.getApplicationType().name(),
                    request.getReviewerNotes() != null ? request.getReviewerNotes() : "Manual rejection");
        }

        return applicationMapper.toResponse(applicationRepository.save(application));
    }

    @Override
    @Transactional
    public ApplicationResponse cancel(Long id, Long userId) {
        Application application = findById(id);

        if (!application.getUserId().equals(userId)) {
            throw new ApplicationException("Cannot cancel another user's application");
        }
        if (application.getStatus() == ApplicationStatus.DISBURSED
                || application.getStatus() == ApplicationStatus.CANCELLED) {
            throw new ApplicationException("Cannot cancel application in status: " + application.getStatus());
        }

        application.setStatus(ApplicationStatus.CANCELLED);
        audit("APPLICATION", id, "CANCELLED", "User-initiated cancellation for user " + userId);
        return applicationMapper.toResponse(applicationRepository.save(application));
    }

    private Long provisionProduct(Application application, String currency) {
        ApplicationType type = application.getApplicationType();

        if (type == ApplicationType.CHECKING_ACCOUNT || type == ApplicationType.SAVINGS_ACCOUNT) {
            String accountType = type == ApplicationType.CHECKING_ACCOUNT ? "CHECKING" : "SAVINGS";
            AccountResponse account = accountClient.createAccount(CreateAccountRequest.builder()
                    .userId(application.getUserId())
                    .accountType(accountType)
                    .initialDeposit(application.getApprovedAmount() != null
                            ? application.getApprovedAmount() : BigDecimal.ZERO)
                    .currency(currency != null ? currency : "USD")
                    .overdraftLimit(type == ApplicationType.CHECKING_ACCOUNT
                            ? new BigDecimal("500.00") : BigDecimal.ZERO)
                    .build());
            return account.getId();
        }

        // For CREDIT_CARD, PERSONAL_LOAN, AUTO_LOAN, MORTGAGE:
        // downstream services (credit-card-service, loan-service) not yet running
        // return placeholder ID — will wire Feign clients when those services are built
        log.info("Product provisioning for {} — downstream service not yet wired, returning placeholder",
                type);
        return -1L;
    }

    private Application findById(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));
    }

    /**
     * Records who did this, not only what was done.
     *
     * <p>The actor comes from the request rather than from an argument.
     * Several call sites used to pass the <em>subject</em> of the change --
     * the account holder, the applicant -- which reads correctly right up
     * until a member of staff acts on a customer's behalf, and then the audit
     * row names the customer as having done it themselves.
     *
     * <p>{@code actorType} is always set. A scheduled job or a Kafka listener
     * has no caller and is recorded as {@code SYSTEM}, so a null
     * {@code performedBy} beside it means "no user was involved" rather than
     * "the attribution was lost".
     */
    private void audit(String entityType, Long entityId, String action, String details) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .performedBy(CallerContext.userId().orElse(null))
                .actorType(CallerContext.actor())
                .details(details)
                .build());
    }
}
