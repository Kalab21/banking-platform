package com.bankingplatform.notification.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.notification.dto.NotificationResponse;
import com.bankingplatform.notification.dto.PagedNotificationsResponse;
import com.bankingplatform.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership authorization on notifications.
 *
 * <p>A notification describes a customer's own activity — money moved, an
 * application decided, a card issued. The user id in the path was taken as the
 * authority for the read rather than as the identifier of whose list was
 * wanted, so every customer's alerts were readable by any other.
 */
@DisplayName("Notification authorization")
class NotificationAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;

    private NotificationService notificationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        notificationService = Mockito.mock(NotificationService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(notificationService))
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

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("a customer reads their own notifications")
        void ownNotificationsAllowed() throws Exception {
            when(notificationService.getNotifications(anyLong(), anyInt(), anyInt()))
                    .thenReturn(PagedNotificationsResponse.builder().build());

            mvc.perform(as(get("/api/notifications/user/{id}", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read another customer's notifications")
        void foreignNotificationsDenied() throws Exception {
            mvc.perform(as(get("/api/notifications/user/{id}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(notificationService, never()).getNotifications(anyLong(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("staff may read any customer's notifications")
        void staffReadsAny() throws Exception {
            when(notificationService.getNotifications(anyLong(), anyInt(), anyInt()))
                    .thenReturn(PagedNotificationsResponse.builder().build());

            mvc.perform(as(get("/api/notifications/user/{id}", CUSTOMER_B), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("marking read")
    class MarkingRead {

        @Test
        @DisplayName("marks the caller's own notification, whatever userId is supplied")
        void markAsReadUsesCallerIdentity() throws Exception {
            // The endpoint used to take the owner as a query parameter and
            // scope the update by it, so naming someone else's id marked their
            // notification read. The parameter is now ignored entirely.
            when(notificationService.markAsRead(anyLong(), anyLong()))
                    .thenReturn(NotificationResponse.builder().id(7L).userId(CUSTOMER_A).build());

            mvc.perform(as(put("/api/notifications/{id}/read", 7L), CUSTOMER_A, "CUSTOMER")
                            .param("userId", String.valueOf(CUSTOMER_B)))
                    .andExpect(status().isOk());

            verify(notificationService).markAsRead(7L, CUSTOMER_A);
            verify(notificationService, never()).markAsRead(7L, CUSTOMER_B);
        }

        @Test
        @DisplayName("a customer cannot mark another customer's list all read")
        void foreignReadAllDenied() throws Exception {
            mvc.perform(as(put("/api/notifications/user/{id}/read-all", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(notificationService, never()).markAllAsRead(anyLong());
        }

        @Test
        @DisplayName("a customer may mark their own list all read")
        void ownReadAllAllowed() throws Exception {
            mvc.perform(as(put("/api/notifications/user/{id}/read-all", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isNoContent());

            verify(notificationService).markAllAsRead(CUSTOMER_A);
        }
    }

    @Nested
    @DisplayName("fail closed")
    class FailClosed {

        @Test
        @DisplayName("a request carrying no gateway identity is rejected")
        void noIdentityRejected() throws Exception {
            mvc.perform(get("/api/notifications/user/{id}", CUSTOMER_A))
                    .andExpect(status().isUnauthorized());

            verify(notificationService, never()).getNotifications(anyLong(), anyInt(), anyInt());
        }
    }
}
