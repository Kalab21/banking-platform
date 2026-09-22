package com.bankingplatform.application.service.impl;

import com.bankingplatform.application.client.AccountClient;
import com.bankingplatform.application.client.UserClient;
import com.bankingplatform.application.dto.AccountResponse;
import com.bankingplatform.application.dto.CreateAccountRequest;
import com.bankingplatform.application.dto.CreateApplicationRequest;
import com.bankingplatform.application.dto.ReviewRequest;
import com.bankingplatform.application.dto.UserResponse;
import com.bankingplatform.application.kafka.producer.ApplicationEventProducer;
import com.bankingplatform.application.mapper.ApplicationMapper;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.application.repository.AuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An application is a request for a product, not a payment into one.
 *
 * <p>Provisioning a deposit account used to pass the application's approved
 * amount through as the new account's opening balance. Applying for a
 * 10,000.00 checking account therefore created 10,000.00, with no payer and no
 * transaction recording where it came from — the balance simply existed. These
 * tests hold the two amounts an application carries, {@code requestedAmount}
 * and {@code approvedAmount}, away from the account-opening call.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApplicationServiceImpl — provisioning cannot fund an account")
class ProvisioningFundingTest {

    private static final long USER_ID = 7L;
    private static final long APPLICATION_ID = 100L;
    private static final BigDecimal REQUESTED = new BigDecimal("10000.00");

    @Mock private ApplicationRepository applicationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private ApplicationMapper applicationMapper;
    @Mock private ApplicationEventProducer eventProducer;
    @Mock private UserClient userClient;
    @Mock private AccountClient accountClient;

    @InjectMocks private ApplicationServiceImpl applicationService;

    @Captor private ArgumentCaptor<CreateAccountRequest> accountRequest;

    private void stubEligibleCustomer() {
        UserResponse user = new UserResponse();
        user.setId(USER_ID);
        user.setEnabled(true);
        user.setKycStatus("VERIFIED");
        // Comfortably above every product minimum, so the application is
        // auto-approved and reaches provisioning.
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
        request.setRequestedAmount(REQUESTED);
        request.setCurrency("USD");
        return request;
    }

    @Test
    @DisplayName("a requested amount never reaches the account-opening call")
    void requestedAmountCannotCreateFunds() {
        stubEligibleCustomer();

        applicationService.submitApplication(submission(ApplicationType.CHECKING_ACCOUNT));

        verify(accountClient).createAccount(accountRequest.capture());
        assertThat(describe(accountRequest.getValue()))
                .doesNotContain("10000");
    }

    @Test
    @DisplayName("a staff-approved amount never reaches the account-opening call")
    void approvedAmountCannotCreateFunds() {
        stubEligibleCustomer();

        Application pending = Application.builder()
                .id(APPLICATION_ID)
                .userId(USER_ID)
                .applicationType(ApplicationType.SAVINGS_ACCOUNT)
                .requestedAmount(REQUESTED)
                .currency("USD")
                .status(ApplicationStatus.UNDER_REVIEW)
                .build();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(pending));

        ReviewRequest review = new ReviewRequest();
        review.setStatus(ApplicationStatus.APPROVED);
        // A reviewer may approve less than was asked for; neither figure is a
        // deposit.
        review.setApprovedAmount(new BigDecimal("8000.00"));

        applicationService.review(APPLICATION_ID, review);

        verify(accountClient).createAccount(accountRequest.capture());
        assertThat(describe(accountRequest.getValue()))
                .doesNotContain("8000")
                .doesNotContain("10000");
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

    /**
     * Lombok's generated {@code toString} names every field and its value, so
     * asserting on it catches an amount that arrives through a field this test
     * does not know about yet — which is the failure worth catching.
     */
    private static String describe(CreateAccountRequest request) {
        return String.valueOf(request);
    }
}
