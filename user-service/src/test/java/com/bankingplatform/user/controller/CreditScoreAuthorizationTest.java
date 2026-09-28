package com.bankingplatform.user.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.user.service.CreditScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Underwriting reads the credit score, so staff adjusting their own would be
 * pricing their own credit. The role check itself is {@code @PreAuthorize},
 * which a standalone MockMvc does not apply; this covers the self rule.
 */
@DisplayName("Credit score changes")
class CreditScoreAuthorizationTest {

    private static final long STAFF = 99L;
    private static final long CUSTOMER = 10L;

    private CreditScoreService scores;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        scores = Mockito.mock(CreditScoreService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CreditScoreController(scores))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();
    }

    private void change(long target, int expected) throws Exception {
        mvc.perform(put("/api/users/{userId}/credit-score", target)
                        .header(CallerIdentityHeaders.USER_ID, String.valueOf(STAFF))
                        .header(CallerIdentityHeaders.USERNAME, "staff")
                        .header(CallerIdentityHeaders.USER_ROLE, "EMPLOYEE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":50,\"reason\":\"Manual review\"}"))
                .andExpect(status().is(expected));
    }

    @Test
    @DisplayName("staff cannot change their own credit score")
    void staffCannotChangeOwnScore() throws Exception {
        change(STAFF, 403);
        verify(scores, never()).updateScore(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("staff may change a customer's credit score")
    void staffMayChangeACustomersScore() throws Exception {
        change(CUSTOMER, 200);
        verify(scores).updateScore(CUSTOMER, 50, "Manual review");
    }
}
