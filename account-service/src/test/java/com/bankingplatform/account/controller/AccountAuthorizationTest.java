package com.bankingplatform.account.controller;

import com.bankingplatform.account.dto.AccountResponse;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.service.AccountService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Object-level authorization on the user-facing account endpoints.
 *
 * <p>These are the tests that would have caught the original defect: the
 * service authenticated every request through the gateway but never checked
 * that the caller owned the account it was asking about, so any customer could
 * read any other customer's balances by changing an id in the URL.
 *
 * <p>Driven through MockMvc rather than the service layer, because the control
 * being tested is the HTTP boundary — including that a denial returns 403 and
 * never reaches the service at all.
 */
@DisplayName("Account authorization")
class AccountAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;

    private static final long ACCOUNT_OF_A = 1L;
    private static final long ACCOUNT_OF_B = 2L;

    private AccountService accountService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        accountService = Mockito.mock(AccountService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new AccountController(accountService))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();

        when(accountService.getAccountById(ACCOUNT_OF_A)).thenReturn(accountOwnedBy(CUSTOMER_A, ACCOUNT_OF_A));
        when(accountService.getAccountById(ACCOUNT_OF_B)).thenReturn(accountOwnedBy(CUSTOMER_B, ACCOUNT_OF_B));
        when(accountService.getAccountByNumber(anyString())).thenReturn(accountOwnedBy(CUSTOMER_B, ACCOUNT_OF_B));
        when(accountService.getAccountsByUserId(anyLong())).thenReturn(List.of());
        when(accountService.getActiveAccountsByUserId(anyLong())).thenReturn(List.of());
    }

    private static AccountResponse accountOwnedBy(long userId, long accountId) {
        AccountResponse response = new AccountResponse();
        response.setId(accountId);
        response.setUserId(userId);
        response.setBalance(new BigDecimal("100.00"));
        return response;
    }

    /** Applies the identity headers exactly as the gateway forwards them. */
    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, role);
    }

    @Nested
    @DisplayName("a customer reaches their own data")
    class OwnData {

        @Test
        @DisplayName("reading an owned account is allowed")
        void ownAccountAllowed() throws Exception {
            mvc.perform(as(get("/api/accounts/{id}", ACCOUNT_OF_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("listing own accounts is allowed")
        void ownCollectionAllowed() throws Exception {
            mvc.perform(as(get("/api/accounts/user/{userId}", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("opening an account for oneself is allowed")
        void openOwnAccountAllowed() throws Exception {
            mvc.perform(as(post("/api/accounts"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":%d,\"accountType\":\"CHECKING\"}".formatted(CUSTOMER_A)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("a customer cannot reach another customer's data")
    class CrossCustomer {

        @Test
        @DisplayName("reading another customer's account by id is denied")
        void otherAccountByIdDenied() throws Exception {
            mvc.perform(as(get("/api/accounts/{id}", ACCOUNT_OF_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("reading another customer's account by account number is denied")
        void otherAccountByNumberDenied() throws Exception {
            mvc.perform(as(get("/api/accounts/number/{n}", "0001112223"), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("listing another customer's accounts is denied, and the service is never queried")
        void otherCollectionDenied() throws Exception {
            mvc.perform(as(get("/api/accounts/user/{userId}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            // The denial happens before any lookup, so a rejected request
            // cannot be used to probe whether the user exists.
            verify(accountService, never()).getAccountsByUserId(CUSTOMER_B);
        }

        @Test
        @DisplayName("listing another customer's active accounts is denied")
        void otherActiveCollectionDenied() throws Exception {
            mvc.perform(as(get("/api/accounts/user/{userId}/active", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("opening an account in another customer's name is denied")
        void openAccountForOtherDenied() throws Exception {
            mvc.perform(as(post("/api/accounts"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":%d,\"accountType\":\"CHECKING\"}".formatted(CUSTOMER_B)))
                    .andExpect(status().isForbidden());

            // The userId in the body is caller input; it must not create an
            // account for someone else.
            verify(accountService, never()).createAccount(any());
        }
    }

    @Nested
    @DisplayName("privileged account operations are staff-only")
    class Privileged {

        @Test
        @DisplayName("a customer cannot change account status, even on their own account")
        void customerCannotChangeStatus() throws Exception {
            mvc.perform(as(put("/api/accounts/{id}/status", ACCOUNT_OF_A), CUSTOMER_A, "CUSTOMER")
                            .param("status", AccountStatus.ACTIVE.name()))
                    .andExpect(status().isForbidden());

            // Otherwise a customer could simply unfreeze an account that fraud
            // detection had frozen.
            verify(accountService, never()).updateStatus(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot raise their own overdraft limit")
        void customerCannotChangeOverdraft() throws Exception {
            mvc.perform(as(put("/api/accounts/{id}/overdraft-limit", ACCOUNT_OF_A), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"overdraftLimit\":100000.00}"))
                    .andExpect(status().isForbidden());

            verify(accountService, never()).updateOverdraftLimit(anyLong(), any());
        }

        @Test
        @DisplayName("an employee may change account status")
        void staffMayChangeStatus() throws Exception {
            mvc.perform(as(put("/api/accounts/{id}/status", ACCOUNT_OF_A), STAFF, "EMPLOYEE")
                            .param("status", AccountStatus.FROZEN.name()))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("staff may act across customers")
    class Staff {

        @Test
        @DisplayName("an employee may read any customer's account")
        void employeeReadsAnyAccount() throws Exception {
            mvc.perform(as(get("/api/accounts/{id}", ACCOUNT_OF_B), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an admin may list any customer's accounts")
        void adminListsAnyCollection() throws Exception {
            mvc.perform(as(get("/api/accounts/user/{userId}", CUSTOMER_B), STAFF, "ADMIN"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the ROLE_ prefix from the token is understood")
        void rolePrefixAccepted() throws Exception {
            mvc.perform(as(get("/api/accounts/{id}", ACCOUNT_OF_B), STAFF, "ROLE_EMPLOYEE"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("missing or malformed identity fails closed")
    class FailClosed {

        @Test
        @DisplayName("no identity headers at all is rejected")
        void noIdentityRejected() throws Exception {
            mvc.perform(get("/api/accounts/{id}", ACCOUNT_OF_A))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an id without a role is rejected")
        void missingRoleRejected() throws Exception {
            mvc.perform(get("/api/accounts/{id}", ACCOUNT_OF_A)
                            .header(CallerIdentityHeaders.USER_ID, String.valueOf(CUSTOMER_A)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a non-numeric caller id is rejected rather than ignored")
        void malformedIdRejected() throws Exception {
            mvc.perform(get("/api/accounts/{id}", ACCOUNT_OF_A)
                            .header(CallerIdentityHeaders.USER_ID, "not-a-number")
                            .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a blank caller id is rejected")
        void blankIdRejected() throws Exception {
            mvc.perform(get("/api/accounts/{id}", ACCOUNT_OF_A)
                            .header(CallerIdentityHeaders.USER_ID, "  ")
                            .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an unrecognised role is not treated as staff")
        void unknownRoleRejected() throws Exception {
            mvc.perform(get("/api/accounts/{id}", ACCOUNT_OF_B)
                            .header(CallerIdentityHeaders.USER_ID, String.valueOf(CUSTOMER_A))
                            .header(CallerIdentityHeaders.USER_ROLE, "SUPERUSER"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
