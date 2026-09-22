package com.bankingplatform.application.controller;

import com.bankingplatform.application.dto.ApplicationResponse;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.service.ApplicationService;
import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization on product applications.
 *
 * <p>An application carries what a customer asked for and what the bank decided.
 * None of it was authorised: the user id arrived in a path, a body or a query
 * string and was used directly, so any authenticated customer could read
 * another's applications, work the staff queue, and approve their own request
 * at an amount of their choosing.
 */
@DisplayName("Application authorization")
class ApplicationAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;

    private ApplicationService applicationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        applicationService = Mockito.mock(ApplicationService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new ApplicationController(applicationService))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();
    }

    private static ApplicationResponse applicationOf(long ownerId) {
        ApplicationResponse response = new ApplicationResponse();
        response.setId(5L);
        response.setUserId(ownerId);
        response.setStatus("SUBMITTED");
        return response;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder,
                                                     long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, role);
    }

    private static String createBody(long userId) {
        return ("{\"userId\":%d,\"applicationType\":\"CHECKING_ACCOUNT\","
                + "\"requestedAmount\":100.00,\"currency\":\"USD\"}").formatted(userId);
    }

    @Nested
    @DisplayName("a customer and their own applications")
    class OwnApplications {

        @Test
        @DisplayName("may submit for themselves")
        void ownSubmitAllowed() throws Exception {
            when(applicationService.submitApplication(any())).thenReturn(applicationOf(CUSTOMER_A));

            mvc.perform(as(post("/api/applications"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(CUSTOMER_A)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("may read their own list")
        void ownListAllowed() throws Exception {
            when(applicationService.getByUserId(CUSTOMER_A)).thenReturn(List.of());

            mvc.perform(as(get("/api/applications/user/{id}", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("may cancel their own application")
        void ownCancelAllowed() throws Exception {
            when(applicationService.getById(5L)).thenReturn(applicationOf(CUSTOMER_A));
            when(applicationService.cancel(anyLong(), anyLong())).thenReturn(applicationOf(CUSTOMER_A));

            mvc.perform(as(put("/api/applications/{id}/cancel", 5L), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());

            // The owner passed on is the stored one, not a caller-supplied id.
            verify(applicationService).cancel(5L, CUSTOMER_A);
        }
    }

    @Nested
    @DisplayName("a customer and someone else's applications")
    class ForeignApplications {

        @Test
        @DisplayName("cannot submit on another customer's behalf")
        void foreignSubmitDenied() throws Exception {
            mvc.perform(as(post("/api/applications"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(CUSTOMER_B)))
                    .andExpect(status().isForbidden());

            verify(applicationService, never()).submitApplication(any());
        }

        @Test
        @DisplayName("cannot read another customer's list")
        void foreignListDenied() throws Exception {
            mvc.perform(as(get("/api/applications/user/{id}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(applicationService, never()).getByUserId(anyLong());
        }

        @Test
        @DisplayName("cannot read another customer's application by id")
        void foreignByIdDenied() throws Exception {
            when(applicationService.getById(5L)).thenReturn(applicationOf(CUSTOMER_B));

            mvc.perform(as(get("/api/applications/{id}", 5L), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("cannot cancel another customer's application")
        void foreignCancelDenied() throws Exception {
            // The service already refused to cancel "another user's
            // application", but compared against a userId from the query
            // string, so naming the victim's id satisfied the check.
            when(applicationService.getById(5L)).thenReturn(applicationOf(CUSTOMER_B));

            mvc.perform(as(put("/api/applications/{id}/cancel", 5L), CUSTOMER_A, "CUSTOMER")
                            .param("userId", String.valueOf(CUSTOMER_B)))
                    .andExpect(status().isForbidden());

            verify(applicationService, never()).cancel(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("staff operations")
    class StaffOperations {

        @Test
        @DisplayName("a customer cannot work the status queue")
        void customerCannotQueryByStatus() throws Exception {
            mvc.perform(as(get("/api/applications/status/{s}", ApplicationStatus.SUBMITTED),
                            CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(applicationService, never()).getByStatus(any());
        }

        @Test
        @DisplayName("a customer cannot decide an application")
        void customerCannotReview() throws Exception {
            // Left open, an applicant could approve their own loan and set the
            // amount.
            mvc.perform(as(put("/api/applications/{id}/review", 5L), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"APPROVE\",\"approvedAmount\":10000.00}"))
                    .andExpect(status().isForbidden());

            verify(applicationService, never()).review(anyLong(), any());
        }

        @Test
        @DisplayName("an employee works the status queue")
        void staffQueryByStatusAllowed() throws Exception {
            when(applicationService.getByStatus(any())).thenReturn(List.of());

            mvc.perform(as(get("/api/applications/status/{s}", ApplicationStatus.SUBMITTED), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an employee decides an application")
        void staffReviewAllowed() throws Exception {
            when(applicationService.review(anyLong(), any())).thenReturn(applicationOf(CUSTOMER_B));

            mvc.perform(as(put("/api/applications/{id}/review", 5L), STAFF, "EMPLOYEE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"APPROVE\",\"approvedAmount\":10000.00}"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("fail closed")
    class FailClosed {

        @Test
        @DisplayName("a request carrying no gateway identity is rejected")
        void noIdentityRejected() throws Exception {
            mvc.perform(get("/api/applications/user/{id}", CUSTOMER_A))
                    .andExpect(status().isUnauthorized());

            verify(applicationService, never()).getByUserId(anyLong());
        }
    }
}
