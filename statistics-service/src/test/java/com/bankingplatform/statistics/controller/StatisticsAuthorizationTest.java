package com.bankingplatform.statistics.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.statistics.dto.DailySnapshotResponse;
import com.bankingplatform.statistics.dto.PlatformStatsResponse;
import com.bankingplatform.statistics.dto.UserStatsResponse;
import com.bankingplatform.statistics.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read which statistics.
 *
 * <p>Platform and daily aggregates describe every customer's activity. They had
 * no role check, so any authenticated customer could read total balances and
 * transaction volumes across the whole platform.
 */
@DisplayName("Statistics authorization")
class StatisticsAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;

    private StatisticsService statisticsService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        statisticsService = Mockito.mock(StatisticsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new StatisticsController(statisticsService))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();

        when(statisticsService.getPlatformStats()).thenReturn(new PlatformStatsResponse());
        when(statisticsService.getUserStats(anyLong())).thenReturn(new UserStatsResponse());
        when(statisticsService.getDailySnapshot(any())).thenReturn(new DailySnapshotResponse());
        when(statisticsService.getDailySnapshots(any(), any())).thenReturn(List.of());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USER_ROLE, role);
    }

    @Nested
    @DisplayName("platform-wide figures are staff-only")
    class PlatformWide {

        @Test
        @DisplayName("a customer cannot read platform statistics")
        void customerDeniedPlatform() throws Exception {
            mvc.perform(as(get("/api/statistics/platform"), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(statisticsService, never()).getPlatformStats();
        }

        @Test
        @DisplayName("a customer cannot read a daily snapshot")
        void customerDeniedDaily() throws Exception {
            mvc.perform(as(get("/api/statistics/daily"), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(statisticsService, never()).getDailySnapshot(any());
        }

        @Test
        @DisplayName("a customer cannot read a daily range")
        void customerDeniedRange() throws Exception {
            mvc.perform(as(get("/api/statistics/daily/range"), CUSTOMER_A, "CUSTOMER")
                            .param("from", LocalDate.now().minusDays(7).toString())
                            .param("to", LocalDate.now().toString()))
                    .andExpect(status().isForbidden());

            verify(statisticsService, never()).getDailySnapshots(any(), any());
        }

        @Test
        @DisplayName("staff may read platform statistics")
        void staffAllowedPlatform() throws Exception {
            mvc.perform(as(get("/api/statistics/platform"), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an admin may read a daily snapshot")
        void adminAllowedDaily() throws Exception {
            mvc.perform(as(get("/api/statistics/daily"), STAFF, "ADMIN"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("per-user figures follow ownership")
    class PerUser {

        @Test
        @DisplayName("a customer may read their own statistics")
        void ownStatsAllowed() throws Exception {
            mvc.perform(as(get("/api/statistics/users/{id}", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read another customer's statistics")
        void foreignStatsDenied() throws Exception {
            mvc.perform(as(get("/api/statistics/users/{id}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(statisticsService, never()).getUserStats(CUSTOMER_B);
        }

        @Test
        @DisplayName("staff may read any customer's statistics")
        void staffReadsAnyStats() throws Exception {
            mvc.perform(as(get("/api/statistics/users/{id}", CUSTOMER_B), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("fail closed")
    class FailClosed {

        // Nested rather than declared at class level: surefire does not run a
        // top-level @Test in a class that also has @Nested classes, so this
        // assertion would have been silently skipped.
        @Test
        @DisplayName("statistics with no gateway identity are rejected")
        void noIdentityRejected() throws Exception {
            mvc.perform(get("/api/statistics/platform"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an unrecognised role cannot read platform statistics")
        void unknownRoleDenied() throws Exception {
            mvc.perform(get("/api/statistics/platform")
                            .header(CallerIdentityHeaders.USER_ID, String.valueOf(CUSTOMER_A))
                            .header(CallerIdentityHeaders.USER_ROLE, "SUPERUSER"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
