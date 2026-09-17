package com.bankingplatform.fraud.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.fraud.model.AlertStatus;
import com.bankingplatform.fraud.model.FraudAlert;
import com.bankingplatform.fraud.service.FraudDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization on fraud alerts.
 *
 * <p>The defect these cover was found by driving the running stack: none of
 * these endpoints took a caller, so any authenticated customer could list every
 * open alert on the platform, read the alerts on any account, and resolve an
 * alert while naming themselves as the reviewer. The subject of a fraud control
 * could switch it off.
 *
 * <p>Each denial also asserts the service was never reached, so a refused
 * request cannot have read or changed an alert.
 */
@DisplayName("Fraud alert authorization")
class FraudAuthorizationTest {

    private static final long CUSTOMER = 10L;
    private static final long STAFF = 99L;
    private static final long OTHER_ACCOUNT = 4242L;

    private FraudDetectionService fraudService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fraudService = Mockito.mock(FraudDetectionService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new FraudAlertController(fraudService))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder,
                                                     long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, role);
    }

    private static FraudAlert alert() {
        return FraudAlert.builder()
                .id(1L)
                .accountId(OTHER_ACCOUNT)
                .alertType("TRANSACTION_FRAUD")
                .riskScore(60)
                .amount(new BigDecimal("30000.00"))
                .status(AlertStatus.OPEN)
                .build();
    }

    @Nested
    @DisplayName("a customer is refused")
    class CustomerRefused {

        @Test
        @DisplayName("cannot list the platform's open alerts")
        void cannotListPlatformAlerts() throws Exception {
            mvc.perform(as(get("/api/fraud/alerts"), CUSTOMER, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(fraudService);
        }

        @Test
        @DisplayName("cannot read the alerts on an account")
        void cannotReadAccountAlerts() throws Exception {
            mvc.perform(as(get("/api/fraud/alerts/account/{id}", OTHER_ACCOUNT), CUSTOMER, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(fraudService);
        }

        @Test
        @DisplayName("cannot review or dismiss an alert")
        void cannotReviewAnAlert() throws Exception {
            // The one that matters most: an alert raised by a customer's own
            // activity must not be closeable by that customer.
            mvc.perform(as(put("/api/fraud/alerts/{id}/review", 1L), CUSTOMER, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"nothing to see\"}"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(fraudService);
        }

        @Test
        @DisplayName("claiming the ADMIN role in a header does not help")
        void spoofedRoleHeaderIsNotTrusted() throws Exception {
            // The gateway overwrites these headers on every routed request.
            // This asserts the service reads what it is given rather than
            // deriving authority from anything the client controls beyond it.
            mvc.perform(get("/api/fraud/alerts")
                            .header(CallerIdentityHeaders.USER_ID, String.valueOf(CUSTOMER))
                            .header(CallerIdentityHeaders.USERNAME, "user" + CUSTOMER)
                            .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER")
                            .header("X-Admin", "true")
                            .header("Role", "ADMIN"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(fraudService);
        }
    }

    @Nested
    @DisplayName("staff may work the queue")
    class StaffAllowed {

        @Test
        @DisplayName("an employee lists open alerts")
        void employeeListsAlerts() throws Exception {
            when(fraudService.getOpenAlerts()).thenReturn(List.of(alert()));

            mvc.perform(as(get("/api/fraud/alerts"), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());

            verify(fraudService).getOpenAlerts();
        }

        @Test
        @DisplayName("an admin reads the alerts on an account")
        void adminReadsAccountAlerts() throws Exception {
            when(fraudService.getAlertsByAccount(anyLong(), anyInt(), anyInt()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(as(get("/api/fraud/alerts/account/{id}", OTHER_ACCOUNT), STAFF, "ADMIN"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("who the reviewer is")
    class ReviewerIdentity {

        @Test
        @DisplayName("is taken from the caller, not from the request body")
        void reviewerComesFromCallerIdentity() throws Exception {
            when(fraudService.reviewAlert(anyLong(), any(), anyLong(), any())).thenReturn(alert());

            // The body carries a reviewedBy that the DTO no longer has. An
            // unknown property must not become the recorded reviewer.
            mvc.perform(as(put("/api/fraud/alerts/{id}/review", 1L), STAFF, "EMPLOYEE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"RESOLVED\",\"reviewedBy\":10,\"resolutionNote\":\"checked\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Long> reviewer = ArgumentCaptor.forClass(Long.class);
            verify(fraudService).reviewAlert(eq(1L), eq(AlertStatus.RESOLVED), reviewer.capture(), any());

            assertThat(reviewer.getValue())
                    .as("reviewer recorded against the alert")
                    .isEqualTo(STAFF);
        }
    }

    @Nested
    @DisplayName("fail closed")
    class FailClosed {

        @Test
        @DisplayName("a request carrying no gateway identity is rejected")
        void noIdentityRejected() throws Exception {
            mvc.perform(get("/api/fraud/alerts"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(fraudService);
        }
    }
}
