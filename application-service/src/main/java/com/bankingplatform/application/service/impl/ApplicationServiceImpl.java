package com.bankingplatform.application.service.impl;

import com.bankingplatform.common.security.CallerContext;
import com.bankingplatform.application.client.AccountClient;
import com.bankingplatform.application.client.UserClient;
import com.bankingplatform.application.dto.*;
import com.bankingplatform.application.exception.ApplicationException;
import com.bankingplatform.application.exception.InvalidApplicationRequestException;
import com.bankingplatform.application.exception.ResourceNotFoundException;
import com.bankingplatform.application.kafka.producer.ApplicationEventProducer;
import com.bankingplatform.application.mapper.ApplicationMapper;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationTransitions;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.model.AuditLog;
import com.bankingplatform.application.model.DecisionSnapshot;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.application.repository.AuditLogRepository;
import com.bankingplatform.application.repository.DecisionSnapshotRepository;
import com.bankingplatform.application.service.ApplicationRequestValidator;
import com.bankingplatform.application.service.ApplicationService;
import com.bankingplatform.application.underwriting.ReasonCode;
import com.bankingplatform.application.underwriting.UnderwritingDecision;
import com.bankingplatform.application.underwriting.UnderwritingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final AuditLogRepository auditLogRepository;
    private final DecisionSnapshotRepository decisionSnapshotRepository;
    private final ApplicationMapper applicationMapper;
    private final ApplicationEventProducer eventProducer;
    private final UserClient userClient;
    private final AccountClient accountClient;
    private final ApplicationRequestValidator validator;
    private final UnderwritingService underwriting;

    @Override
    @Transactional
    public ApplicationResponse submitApplication(CreateApplicationRequest request) {
        validator.validate(request);

        UserResponse user = userClient.getUserById(request.getUserId());

        if (!user.isEnabled()) {
            throw new ApplicationException("User account is disabled");
        }

        int creditScore = user.getCreditScore() != null ? user.getCreditScore() : 0;

        Application application = Application.builder()
                .userId(request.getUserId())
                .applicationType(request.getApplicationType())
                .requestedAmount(request.getRequestedAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .termMonths(request.getTermMonths())
                .purpose(request.getPurpose())
                .annualIncome(request.getAnnualIncome())
                .monthlyDebtObligations(request.getMonthlyDebtObligations())
                .assetValue(request.getAssetValue())
                .downPayment(request.getDownPayment())
                .creditScoreAtApply(creditScore)
                .build();

        // Every application is submitted before it is decided, and
        // statistics-service counts submissions \u2014 but nothing had ever called
        // publishApplicationSubmitted, so that counter had always read zero.
        //
        // Saved first so the event can name an id that exists, then published
        // inside this transaction rather than after it. The outbox is a
        // database row, so it rolls back with the application: if provisioning
        // below fails, the row and the publication disappear together.
        Application submitted = applicationRepository.save(application);
        eventProducer.publishApplicationSubmitted(
                submitted.getId(), request.getUserId(), request.getApplicationType().name());

        // Assessment begins immediately, but it is still a state the
        // application passes through rather than one it skips.
        move(submitted, ApplicationStatus.UNDER_REVIEW);

        UnderwritingDecision decision =
                underwriting.decide(submitted, user.getCreditScore(), user.getKycStatus());
        recordDecision(submitted, decision, DecisionSnapshot.DecidedBy.POLICY, null,
                user.getCreditScore(), user.getKycStatus());
        submitted.setReviewedAt(LocalDateTime.now());

        if (decision.isApproved()) {
            submitted.setApprovedAmount(decision.approvedAmount());
            return applicationMapper.toResponse(approve(submitted, "AUTO_APPROVED"));
        }

        if (decision.isReferred()) {
            // Policy did not refuse it and will not approve it on its own. The
            // application waits for a person, which is a real state it can sit
            // in rather than a decision dressed up as one.
            move(submitted, ApplicationStatus.MANUAL_REVIEW);
            Application referred = applicationRepository.save(submitted);
            audit("APPLICATION", referred.getId(), "AUTO_REFERRED",
                    "Type: " + request.getApplicationType()
                            + ", Reasons: " + reasonList(decision));
            return applicationMapper.toResponse(referred);
        }

        move(submitted, ApplicationStatus.REJECTED);
        Application saved = applicationRepository.save(submitted);
        audit("APPLICATION", saved.getId(), "AUTO_REJECTED",
                "Type: " + request.getApplicationType() + ", Reasons: " + reasonList(decision));
        eventProducer.publishApplicationRejected(saved.getId(), request.getUserId(),
                request.getApplicationType().name(), reasonList(decision));

        return applicationMapper.toResponse(saved);
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

        if (application.getStatus().isTerminal()) {
            throw new ApplicationException(
                    "Cannot review application in status: " + application.getStatus());
        }

        application.setReviewedAt(LocalDateTime.now());
        if (request.getReviewerNotes() != null) {
            application.setReviewerNotes(request.getReviewerNotes());
        }

        // A reviewer's decision is a decision, and is recorded the same way the
        // policy's is. Without this the audit trail would explain every
        // automatic outcome and none of the human ones, which is the wrong way
        // round: a person's judgement is the part worth being able to review.
        BigDecimal reviewerApproved = request.getDecision() == ReviewDecision.APPROVE
                ? approvedAmount(application, request) : null;
        recordDecision(application,
                new UnderwritingDecision(
                        UnderwritingDecision.Outcome.valueOf(request.getDecision().name()),
                        List.of(ReasonCode.MANUAL_REVIEW_REQUIRED),
                        reviewerApproved, null, null, underwriting.policyVersion()),
                DecisionSnapshot.DecidedBy.REVIEWER, CallerContext.userId().orElse(null),
                application.getCreditScoreAtApply(), null);

        switch (request.getDecision()) {
            case REFER -> {
                move(application, ApplicationStatus.MANUAL_REVIEW);
                audit("APPLICATION", id, "REFERRED", request.getReviewerNotes());
            }
            case REJECT -> {
                move(application, ApplicationStatus.REJECTED);
                audit("APPLICATION", id, "MANUAL_REJECTED", request.getReviewerNotes());
                eventProducer.publishApplicationRejected(id, application.getUserId(),
                        application.getApplicationType().name(),
                        request.getReviewerNotes() != null
                                ? request.getReviewerNotes() : "Manual rejection");
            }
            case APPROVE -> {
                application.setApprovedAmount(reviewerApproved);
                return applicationMapper.toResponse(approve(application, "MANUAL_APPROVED"));
            }
        }

        return applicationMapper.toResponse(applicationRepository.save(application));
    }

    /**
     * A reviewer may approve less than was asked for, never more.
     *
     * <p>Approving above the request means the figure came from somewhere
     * other than the application, and would commit the customer to terms they
     * never asked for.
     */
    private BigDecimal approvedAmount(Application application, ReviewRequest request) {
        BigDecimal requested = application.getRequestedAmount();
        BigDecimal approved = request.getApprovedAmount();
        if (approved == null) {
            return requested;
        }
        if (!ApplicationTransitions.isCreditProduct(application.getApplicationType())) {
            // There is no amount to approve on a deposit account: it opens
            // empty whatever a reviewer types.
            throw new InvalidApplicationRequestException(
                    "approvedAmount does not apply to a " + application.getApplicationType()
                            + " application");
        }
        if (approved.signum() <= 0) {
            throw new InvalidApplicationRequestException("approvedAmount must be greater than zero");
        }
        if (requested != null && approved.compareTo(requested) > 0) {
            throw new InvalidApplicationRequestException(
                    "approvedAmount cannot exceed the requested amount");
        }
        return approved;
    }

    /**
     * Carries an approved application as far as it can honestly go.
     *
     * <p>A deposit account is created here and now, so it reaches
     * {@code PROVISIONED} holding the account's real id. A credit product
     * cannot: the card or loan is created by the service that owns it, in
     * response to the event published below, and this service does not yet
     * hear back. The application therefore stops at {@code PROVISIONING} \u2014 a
     * product has been asked for. Saying {@code PROVISIONED} here would claim
     * a product exists on the strength of having sent a message.
     */
    private Application approve(Application application, String auditAction) {
        ApplicationType type = application.getApplicationType();

        if (ApplicationTransitions.isCreditProduct(type)) {
            // The offer stage does not exist yet, so an approved credit
            // application passes through the states it will later occupy for
            // real. Sequencing, not a shortcut worth keeping.
            move(application, ApplicationStatus.OFFERED);
            move(application, ApplicationStatus.ACCEPTED);
        }
        move(application, ApplicationStatus.PROVISIONING);

        Long productId = provisionProduct(application, application.getCurrency());
        if (productId != null) {
            application.setProductId(productId);
            move(application, ApplicationStatus.PROVISIONED);
        }

        Application saved = applicationRepository.save(application);
        audit("APPLICATION", saved.getId(), auditAction,
                "Type: " + type + ", ProductId: " + saved.getProductId());
        // Both figures go out. The product is funded from the approved one:
        // a reviewer who approves 8,000 against a request for 10,000 has
        // agreed to lend 8,000, and publishing only the request is how the
        // loan came to be written for the larger number.
        eventProducer.publishApplicationApproved(saved.getId(), saved.getUserId(),
                type.name(), saved.getProductId(),
                saved.getCreditScoreAtApply(), saved.getRequestedAmount(),
                saved.getApprovedAmount());
        return saved;
    }

    /**
     * Copies the figures the decision was taken on into a row that is never
     * updated.
     *
     * <p>The alternative is to explain a decision from the customer's profile
     * as it stands when someone asks, which is a different profile: scores
     * move, income is restated, debts are paid down. The snapshot is what makes
     * "why was this refused" answerable months later.
     */
    private void recordDecision(Application application, UnderwritingDecision decision,
                                DecisionSnapshot.DecidedBy decidedBy, Long reviewerId,
                                Integer creditScore, String kycStatus) {
        decisionSnapshotRepository.save(DecisionSnapshot.builder()
                .applicationId(application.getId())
                .policyVersion(decision.policyVersion())
                .decidedBy(decidedBy)
                .reviewerId(reviewerId)
                .decision(DecisionSnapshot.Decision.valueOf(decision.outcome().name()))
                .creditScoreAtDecision(creditScore)
                .kycStatusAtDecision(kycStatus)
                .annualIncomeAtDecision(application.getAnnualIncome())
                .monthlyDebtAtDecision(application.getMonthlyDebtObligations())
                .dtiAtDecision(decision.dti())
                .ltvAtDecision(decision.ltv())
                .assetValueAtDecision(application.getAssetValue())
                .requestedAmountAtDecision(application.getRequestedAmount())
                .requestedTermAtDecision(application.getTermMonths())
                // A refusal lends nothing, and the database refuses a row that
                // says otherwise.
                .approvedAmount(decision.isRejected() ? null : decision.approvedAmount())
                .reasonCodes(new ArrayList<>(decision.reasonCodes()))
                .build());
    }

    /** The reason codes as one field, for an audit line and a rejection notice. */
    private String reasonList(UnderwritingDecision decision) {
        return decision.reasonCodes().stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    /** Applies a status change, or refuses it. */
    private void move(Application application, ApplicationStatus to) {
        ApplicationTransitions.assertCanMove(
                application.getApplicationType(), application.getStatus(), to);
        application.setStatus(to);
    }

    @Override
    @Transactional
    public ApplicationResponse cancel(Long id, Long userId) {
        Application application = findById(id);

        if (!application.getUserId().equals(userId)) {
            throw new ApplicationException("Cannot cancel another user's application");
        }
        // Cancelling after a product exists is refused by the transition
        // rules: PROVISIONING and PROVISIONED have no route to CANCELLED,
        // because withdrawing an application cannot un-issue a card.
        move(application, ApplicationStatus.CANCELLED);
        audit("APPLICATION", id, "CANCELLED", "User-initiated cancellation for user " + userId);
        return applicationMapper.toResponse(applicationRepository.save(application));
    }

    private Long provisionProduct(Application application, String currency) {
        ApplicationType type = application.getApplicationType();

        if (type == ApplicationType.CHECKING_ACCOUNT || type == ApplicationType.SAVINGS_ACCOUNT) {
            String accountType = type == ApplicationType.CHECKING_ACCOUNT ? "CHECKING" : "SAVINGS";
            // An application is a request, not a funding source. Opening a
            // deposit account used to pass the approved amount through as the
            // opening balance, so applying for a 10,000.00 checking account
            // created 10,000.00 out of nothing: no payer, no transaction row,
            // no double entry anywhere. A new account opens empty and is
            // funded afterwards through the money-movement path, which records
            // what moved and from where.
            AccountResponse account = accountClient.createAccount(CreateAccountRequest.builder()
                    .userId(application.getUserId())
                    .accountType(accountType)
                    .currency(currency != null ? currency : "USD")
                    .overdraftLimit(type == ApplicationType.CHECKING_ACCOUNT
                            ? new BigDecimal("500.00") : BigDecimal.ZERO)
                    .build());
            return account.getId();
        }

        // A credit product is created by the service that owns it, from the
        // ApplicationApproved event. Null says "not created here, and not
        // known yet", which is the truth. The previous -1 was written into
        // product_id as though it were an identifier, so an application
        // claimed a product and pointed at one that cannot exist.
        log.debug("{} is provisioned downstream; no product id is known yet", type);
        return null;
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
