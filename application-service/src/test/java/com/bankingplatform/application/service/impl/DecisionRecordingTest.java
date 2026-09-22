package com.bankingplatform.application.service.impl;

import com.bankingplatform.application.client.AccountClient;
import com.bankingplatform.application.client.UserClient;
import com.bankingplatform.application.dto.CreateApplicationRequest;
import com.bankingplatform.application.dto.ReviewDecision;
import com.bankingplatform.application.dto.ReviewRequest;
import com.bankingplatform.application.dto.UserResponse;
import com.bankingplatform.application.kafka.producer.ApplicationEventProducer;
import com.bankingplatform.application.mapper.ApplicationMapper;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.model.DecisionSnapshot;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.application.repository.AuditLogRepository;
import com.bankingplatform.application.repository.DecisionSnapshotRepository;
import com.bankingplatform.application.service.ApplicationRequestValidator;
import com.bankingplatform.application.underwriting.ReasonCode;
import com.bankingplatform.application.underwriting.UnderwritingPolicy;
import com.bankingplatform.application.underwriting.UnderwritingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every decision leaves a record of what it was decided on.
 *
 * <p>Before this, an application carried its outcome and a line of reviewer
 * prose, and the figures behind the outcome were the customer's live profile.
 * Asking "why was this refused" three months later read today's score, today's
 * income and today's debts — so an application refused for a ratio that has
 * since improved looked as though it had been refused for nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Recording a decision")
class DecisionRecordingTest {

    private static final long USER_ID = 7L;
    private static final long APPLICATION_ID = 100L;

    @Mock private ApplicationRepository applicationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private DecisionSnapshotRepository decisionSnapshotRepository;
    @Mock private ApplicationMapper applicationMapper;
    @Mock private ApplicationEventProducer eventProducer;
    @Mock private UserClient userClient;
    @Mock private AccountClient accountClient;
    @Mock private com.bankingplatform.application.service.OfferService offerService;

    @Spy private ApplicationRequestValidator validator = new ApplicationRequestValidator();
    @Spy private UnderwritingService underwriting = new UnderwritingService(new UnderwritingPolicy());

    @InjectMocks private ApplicationServiceImpl applicationService;

    private void customer(Integer creditScore, String kycStatus) {
        UserResponse user = new UserResponse();
        user.setId(USER_ID);
        user.setEnabled(true);
        user.setCreditScore(creditScore);
        user.setKycStatus(kycStatus);
        when(userClient.getUserById(USER_ID)).thenReturn(user);

        when(decisionSnapshotRepository.save(any(DecisionSnapshot.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> {
            Application saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(APPLICATION_ID);
            }
            return saved;
        });
    }

    private CreateApplicationRequest personalLoan() {
        CreateApplicationRequest request = new CreateApplicationRequest();
        request.setUserId(USER_ID);
        request.setApplicationType(ApplicationType.PERSONAL_LOAN);
        request.setCurrency("USD");
        request.setRequestedAmount(new BigDecimal("10000.00"));
        request.setTermMonths(48);
        request.setPurpose("Home improvement");
        request.setAnnualIncome(new BigDecimal("90000.00"));
        request.setMonthlyDebtObligations(new BigDecimal("1500.00"));
        return request;
    }

    private DecisionSnapshot captureSnapshot() {
        ArgumentCaptor<DecisionSnapshot> captor = ArgumentCaptor.forClass(DecisionSnapshot.class);
        verify(decisionSnapshotRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("an automatic approval records the figures it was taken on")
    void approvalRecordsItsInputs() {
        customer(780, "APPROVED");

        applicationService.submitApplication(personalLoan());

        DecisionSnapshot snapshot = captureSnapshot();
        assertThat(snapshot.getDecision()).isEqualTo(DecisionSnapshot.Decision.APPROVE);
        assertThat(snapshot.getDecidedBy()).isEqualTo(DecisionSnapshot.DecidedBy.POLICY);
        assertThat(snapshot.getReviewerId()).isNull();
        assertThat(snapshot.getCreditScoreAtDecision()).isEqualTo(780);
        assertThat(snapshot.getKycStatusAtDecision()).isEqualTo("APPROVED");
        assertThat(snapshot.getAnnualIncomeAtDecision()).isEqualByComparingTo(new BigDecimal("90000.00"));
        assertThat(snapshot.getMonthlyDebtAtDecision()).isEqualByComparingTo(new BigDecimal("1500.00"));
        // 1,500 / (90,000 / 12) = 0.2000
        assertThat(snapshot.getDtiAtDecision()).isEqualByComparingTo(new BigDecimal("0.2000"));
        assertThat(snapshot.getRequestedAmountAtDecision()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(snapshot.getRequestedTermAtDecision()).isEqualTo(48);
        assertThat(snapshot.getPolicyVersion()).isNotBlank();
    }

    @Test
    @DisplayName("a refusal records why, in codes rather than prose")
    void refusalRecordsReasonCodes() {
        customer(500, "APPROVED");

        applicationService.submitApplication(personalLoan());

        DecisionSnapshot snapshot = captureSnapshot();
        assertThat(snapshot.getDecision()).isEqualTo(DecisionSnapshot.Decision.REJECT);
        assertThat(snapshot.getReasonCodes()).contains(ReasonCode.CREDIT_SCORE_BELOW_MINIMUM);
        // The database refuses a rejection that lends something, and so does this.
        assertThat(snapshot.getApprovedAmount()).isNull();
    }

    @Test
    @DisplayName("an application the policy will not decide waits in manual review")
    void referredApplicationWaitsForAPerson() {
        // 620 clears the personal-loan minimum of 600 but is under the 640 the
        // policy approves on its own, so it is neither a yes nor a no.
        customer(620, "APPROVED");

        applicationService.submitApplication(personalLoan());

        ArgumentCaptor<Application> saved = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ApplicationStatus.MANUAL_REVIEW);

        DecisionSnapshot snapshot = captureSnapshot();
        assertThat(snapshot.getDecision()).isEqualTo(DecisionSnapshot.Decision.REFER);
        assertThat(snapshot.getReasonCodes()).contains(ReasonCode.CREDIT_SCORE_BELOW_MINIMUM);
    }

    @Test
    @DisplayName("a referred application is not provisioned")
    void referredApplicationCreatesNothing() {
        customer(620, "APPROVED");

        applicationService.submitApplication(personalLoan());

        verify(accountClient, org.mockito.Mockito.never()).createAccount(any());
        verify(eventProducer, org.mockito.Mockito.never())
                .publishApplicationApproved(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a reviewer's decision is recorded as the reviewer's, not the policy's")
    void reviewerDecisionIsAttributed() {
        Application referred = Application.builder()
                .id(APPLICATION_ID)
                .userId(USER_ID)
                .applicationType(ApplicationType.PERSONAL_LOAN)
                .requestedAmount(new BigDecimal("10000.00"))
                .termMonths(48)
                .currency("USD")
                .creditScoreAtApply(620)
                .status(ApplicationStatus.MANUAL_REVIEW)
                .build();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(referred));
        when(applicationRepository.save(any(Application.class))).thenAnswer(i -> i.getArgument(0));

        ReviewRequest review = new ReviewRequest();
        review.setDecision(ReviewDecision.REJECT);
        review.setReviewerNotes("Not this time");

        applicationService.review(APPLICATION_ID, review);

        DecisionSnapshot snapshot = captureSnapshot();
        assertThat(snapshot.getDecidedBy()).isEqualTo(DecisionSnapshot.DecidedBy.REVIEWER);
        assertThat(snapshot.getDecision()).isEqualTo(DecisionSnapshot.Decision.REJECT);
        assertThat(snapshot.getApprovedAmount()).isNull();
    }

    @Test
    @DisplayName("a snapshot has no way to be changed after it is written")
    void snapshotIsWriteOnce() {
        // The guarantee is structural rather than behavioural: if a setter is
        // ever added, the decision stops being a record of what was decided and
        // becomes a mutable opinion about it. The database agrees — every
        // column is declared updatable = false.
        assertThat(DecisionSnapshot.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }
}
