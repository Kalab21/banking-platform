package com.bankingplatform.application.service.impl;

import com.bankingplatform.application.client.AccountClient;
import com.bankingplatform.application.client.UserClient;
import com.bankingplatform.application.dto.AccountResponse;
import com.bankingplatform.application.dto.CreateAccountRequest;
import com.bankingplatform.application.dto.CreateApplicationRequest;
import com.bankingplatform.application.dto.ReviewDecision;
import com.bankingplatform.application.dto.ReviewRequest;
import com.bankingplatform.application.dto.UserResponse;
import com.bankingplatform.application.exception.InvalidApplicationRequestException;
import com.bankingplatform.application.kafka.producer.ApplicationEventProducer;
import com.bankingplatform.application.mapper.ApplicationMapper;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.application.repository.AuditLogRepository;
import com.bankingplatform.application.service.ApplicationRequestValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An application is a request for a product, not a payment into one.
 *
 * <p>Provisioning a deposit account used to pass the application's approved
 * amount through as the new account's opening balance. Applying for a
 * 10,000.00 checking account therefore created 10,000.00, with no payer and
 * nothing recording where it came from.
 *
 * <p>Two layers hold that shut now. A deposit application cannot state an
 * amount at all, and the account-opening request has no field an amount could
 * travel in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApplicationServiceImpl — provisioning cannot fund an account")
class ProvisioningFundingTest {

    private static final long USER_ID = 7L;
    private static final long APPLICATION_ID = 100L;

    @Mock private ApplicationRepository applicationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private ApplicationMapper applicationMapper;
    @Mock private ApplicationEventProducer eventProducer;
    @Mock private UserClient userClient;
    @Mock private AccountClient accountClient;

    /** Real rules, not a stub: the refusals below are the point of the test. */
    @Spy private ApplicationRequestValidator validator = new ApplicationRequestValidator();

    @InjectMocks private ApplicationServiceImpl applicationService;

    @Captor private ArgumentCaptor<CreateAccountRequest> accountRequest;

    private void stubEligibleCustomer() {
        UserResponse user = new UserResponse();
        user.setId(USER_ID);
        user.setEnabled(true);
        user.setKycStatus("VERIFIED");
        // Above every product minimum, so an application is auto-approved and
        // reaches provisioning.
        user.setCreditScore(780);
        when(userClient.getUserById(USER_ID)).thenReturn(user);

        AccountResponse opened = new AccountResponse();
        opened.setId(55L);
        when(accountClient.createAccount(any(CreateAccountRequest.class))).thenReturn(opened);

        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> {
            Application saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(APPLICATION_ID);
            }
            return saved;
        });
    }

    private CreateApplicationRequest submission(ApplicationType type) {
        CreateApplicationRequest request = new CreateApplicationRequest();
        request.setUserId(USER_ID);
        request.setApplicationType(type);
        request.setCurrency("USD");
        return request;
    }

    @Test
    @DisplayName("a deposit application cannot even state an amount")
    void depositApplicationCannotStateAnAmount() {
        stubEligibleCustomer();

        CreateApplicationRequest request = submission(ApplicationType.CHECKING_ACCOUNT);
        request.setRequestedAmount(new BigDecimal("10000.00"));

        assertThatThrownBy(() -> applicationService.submitApplication(request))
                .isInstanceOf(InvalidApplicationRequestException.class)
                .hasMessageContaining("requestedAmount");

        // Refused before anything was opened.
        verify(accountClient, never()).createAccount(any());
    }

    @Test
    @DisplayName("opening an account passes no amount of any kind")
    void openingPassesNoAmount() {
        stubEligibleCustomer();

        applicationService.submitApplication(submission(ApplicationType.SAVINGS_ACCOUNT));

        verify(accountClient).createAccount(accountRequest.capture());
        // Lombok's toString names every field and its value, so this catches an
        // amount arriving through a field the test does not know about yet.
        assertThat(String.valueOf(accountRequest.getValue()))
                .doesNotContain("10000")
                .doesNotContainIgnoringCase("deposit");
    }

    @Test
    @DisplayName("an approved deposit application ends up holding the account's real id")
    void depositReachesProvisioned() {
        stubEligibleCustomer();

        applicationService.submitApplication(submission(ApplicationType.CHECKING_ACCOUNT));

        ArgumentCaptor<Application> saved = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        Application last = saved.getValue();
        assertThat(last.getStatus()).isEqualTo(ApplicationStatus.PROVISIONED);
        assertThat(last.getProductId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("an approved credit application stops at PROVISIONING with no product id")
    void creditStopsAtProvisioning() {
        stubEligibleCustomer();

        CreateApplicationRequest request = submission(ApplicationType.CREDIT_CARD);
        request.setAnnualIncome(new BigDecimal("90000.00"));
        request.setMonthlyDebtObligations(new BigDecimal("400.00"));

        applicationService.submitApplication(request);

        ArgumentCaptor<Application> saved = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        Application last = saved.getValue();
        // The card is created by credit-card-service from the event; this
        // service has not heard back, so it must not claim otherwise.
        assertThat(last.getStatus()).isEqualTo(ApplicationStatus.PROVISIONING);
        assertThat(last.getProductId()).isNull();
        verify(accountClient, never()).createAccount(any());
    }

    @Test
    @DisplayName("a reviewer cannot approve an amount onto a deposit account")
    void reviewRefusesAnAmountOnADepositAccount() {
        stubEligibleCustomer();

        Application pending = Application.builder()
                .id(APPLICATION_ID)
                .userId(USER_ID)
                .applicationType(ApplicationType.SAVINGS_ACCOUNT)
                .currency("USD")
                .status(ApplicationStatus.UNDER_REVIEW)
                .build();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(pending));

        ReviewRequest review = new ReviewRequest();
        review.setDecision(ReviewDecision.APPROVE);
        review.setApprovedAmount(new BigDecimal("8000.00"));

        assertThatThrownBy(() -> applicationService.review(APPLICATION_ID, review))
                .isInstanceOf(InvalidApplicationRequestException.class)
                .hasMessageContaining("approvedAmount");

        verify(accountClient, never()).createAccount(any());
    }

    @Test
    @DisplayName("a reviewer cannot approve more than was asked for")
    void reviewRefusesMoreThanRequested() {
        stubEligibleCustomer();

        Application pending = Application.builder()
                .id(APPLICATION_ID)
                .userId(USER_ID)
                .applicationType(ApplicationType.PERSONAL_LOAN)
                .requestedAmount(new BigDecimal("10000.00"))
                .termMonths(48)
                .currency("USD")
                .status(ApplicationStatus.UNDER_REVIEW)
                .build();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(pending));

        ReviewRequest review = new ReviewRequest();
        review.setDecision(ReviewDecision.APPROVE);
        review.setApprovedAmount(new BigDecimal("25000.00"));

        assertThatThrownBy(() -> applicationService.review(APPLICATION_ID, review))
                .isInstanceOf(InvalidApplicationRequestException.class)
                .hasMessageContaining("cannot exceed the requested amount");
    }

    @Test
    @DisplayName("an approval publishes the amount that was approved, not the amount requested")
    void approvalPublishesTheApprovedAmount() {
        stubEligibleCustomer();

        Application pending = Application.builder()
                .id(APPLICATION_ID)
                .userId(USER_ID)
                .applicationType(ApplicationType.PERSONAL_LOAN)
                .requestedAmount(new BigDecimal("10000.00"))
                .termMonths(48)
                .currency("USD")
                .status(ApplicationStatus.UNDER_REVIEW)
                .build();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(pending));

        ReviewRequest review = new ReviewRequest();
        review.setDecision(ReviewDecision.APPROVE);
        review.setApprovedAmount(new BigDecimal("8000.00"));

        applicationService.review(APPLICATION_ID, review);

        // loan-service builds the loan from this event. Publishing the
        // requested amount here is what wrote a 10,000 loan against an
        // 8,000 approval.
        ArgumentCaptor<BigDecimal> requested = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> approved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(eventProducer).publishApplicationApproved(
                eq(APPLICATION_ID), eq(USER_ID), eq("PERSONAL_LOAN"), any(),
                any(), requested.capture(), approved.capture());

        assertThat(requested.getValue()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(approved.getValue()).isEqualByComparingTo(new BigDecimal("8000.00"));
    }

    @Test
    @DisplayName("the account-opening request has no field that could carry a balance")
    void accountRequestHasNoFundingField() {
        String[] fields = Arrays.stream(CreateAccountRequest.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .toArray(String[]::new);

        assertThat(fields)
                .containsExactlyInAnyOrder("userId", "accountType", "currency", "overdraftLimit");
    }
}
