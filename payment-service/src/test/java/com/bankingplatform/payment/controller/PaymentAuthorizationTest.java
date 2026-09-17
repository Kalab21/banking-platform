package com.bankingplatform.payment.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.payment.client.AccountClient;
import com.bankingplatform.payment.dto.AccountResponse;
import com.bankingplatform.payment.dto.BeneficiaryResponse;
import com.bankingplatform.payment.dto.PaymentResponse;
import com.bankingplatform.payment.security.PaymentOwnershipVerifier;
import com.bankingplatform.payment.service.BeneficiaryService;
import com.bankingplatform.payment.service.PaymentService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership authorization on payments and saved payees.
 *
 * <p>The defect these cover: a {@code userId} in a path or query string, and an
 * account id in a path, were used directly. Naming another customer's id
 * returned their payees, their payments, and let their payee be deleted.
 *
 * <p>A payment belongs to the account it is paid from, so the payment cases
 * resolve that account's owner rather than trusting the path.
 */
@DisplayName("Payment and beneficiary authorization")
class PaymentAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;

    private static final long ACCOUNT_OF_A = 1L;
    private static final long ACCOUNT_OF_B = 2L;

    private BeneficiaryService beneficiaryService;
    private PaymentService paymentService;
    private AccountClient accountClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        beneficiaryService = Mockito.mock(BeneficiaryService.class);
        paymentService = Mockito.mock(PaymentService.class);
        accountClient = Mockito.mock(AccountClient.class);

        PaymentOwnershipVerifier ownership = new PaymentOwnershipVerifier(accountClient);

        mvc = MockMvcBuilders
                .standaloneSetup(
                        new BeneficiaryController(beneficiaryService),
                        new PaymentController(paymentService, ownership))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();

        when(accountClient.getAccountById(ACCOUNT_OF_A)).thenReturn(account(ACCOUNT_OF_A, CUSTOMER_A));
        when(accountClient.getAccountById(ACCOUNT_OF_B)).thenReturn(account(ACCOUNT_OF_B, CUSTOMER_B));
    }

    private static AccountResponse account(long id, long ownerId) {
        AccountResponse response = new AccountResponse();
        response.setId(id);
        response.setUserId(ownerId);
        return response;
    }

    private static BeneficiaryResponse beneficiaryOf(long ownerId) {
        BeneficiaryResponse response = new BeneficiaryResponse();
        response.setId(7L);
        response.setUserId(ownerId);
        return response;
    }

    private static PaymentResponse paymentFrom(long payerAccountId) {
        PaymentResponse response = new PaymentResponse();
        response.setId(5L);
        response.setPayerAccountId(payerAccountId);
        return response;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder,
                                                     long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, role);
    }

    @Nested
    @DisplayName("beneficiaries")
    class Beneficiaries {

        @Test
        @DisplayName("a customer reads their own payees")
        void ownPayeesAllowed() throws Exception {
            when(beneficiaryService.getByUserId(CUSTOMER_A)).thenReturn(List.of());

            mvc.perform(as(get("/api/payments/beneficiaries/user/{id}", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read another customer's payees")
        void foreignPayeesDenied() throws Exception {
            mvc.perform(as(get("/api/payments/beneficiaries/user/{id}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(beneficiaryService, never()).getByUserId(anyLong());
        }

        @Test
        @DisplayName("a customer cannot read another customer's payee by id")
        void foreignPayeeByIdDenied() throws Exception {
            when(beneficiaryService.getById(7L)).thenReturn(beneficiaryOf(CUSTOMER_B));

            mvc.perform(as(get("/api/payments/beneficiaries/{id}", 7L), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a customer cannot delete another customer's payee")
        void foreignPayeeDeleteDenied() throws Exception {
            // The old signature took the owner as a query parameter, so naming
            // the victim's id was enough to delete their payee.
            when(beneficiaryService.getById(7L)).thenReturn(beneficiaryOf(CUSTOMER_B));

            mvc.perform(as(delete("/api/payments/beneficiaries/{id}", 7L), CUSTOMER_A, "CUSTOMER")
                            .param("userId", String.valueOf(CUSTOMER_B)))
                    .andExpect(status().isForbidden());

            verify(beneficiaryService, never()).delete(anyLong(), anyLong());
        }

        @Test
        @DisplayName("a customer cannot add a payee for someone else")
        void foreignPayeeCreateDenied() throws Exception {
            mvc.perform(as(post("/api/payments/beneficiaries"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{\"userId\":%d,\"name\":\"Payee\","
                                    + "\"beneficiaryType\":\"EXTERNAL_ACH\",\"accountNumber\":\"1234567890\"}")
                                    .formatted(CUSTOMER_B)))
                    .andExpect(status().isForbidden());

            verify(beneficiaryService, never()).create(any());
        }

        @Test
        @DisplayName("staff may read any customer's payees")
        void staffReadsAnyPayees() throws Exception {
            when(beneficiaryService.getByUserId(CUSTOMER_B)).thenReturn(List.of());

            mvc.perform(as(get("/api/payments/beneficiaries/user/{id}", CUSTOMER_B), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("payments")
    class Payments {

        @Test
        @DisplayName("a customer reads payments on their own account")
        void ownAccountPaymentsAllowed() throws Exception {
            when(paymentService.getByPayerAccount(ACCOUNT_OF_A)).thenReturn(List.of());

            mvc.perform(as(get("/api/payments/account/{id}", ACCOUNT_OF_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read payments on another customer's account")
        void foreignAccountPaymentsDenied() throws Exception {
            mvc.perform(as(get("/api/payments/account/{id}", ACCOUNT_OF_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(paymentService, never()).getByPayerAccount(anyLong());
        }

        @Test
        @DisplayName("a customer cannot read another customer's scheduled payments")
        void foreignScheduledDenied() throws Exception {
            mvc.perform(as(get("/api/payments/account/{id}/scheduled", ACCOUNT_OF_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(paymentService, never()).getScheduledByPayerAccount(anyLong());
        }

        @Test
        @DisplayName("a customer cannot read another customer's payment by id")
        void foreignPaymentByIdDenied() throws Exception {
            when(paymentService.getById(5L)).thenReturn(paymentFrom(ACCOUNT_OF_B));

            mvc.perform(as(get("/api/payments/{id}", 5L), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a payment reference is not permission to read it")
        void foreignPaymentByRefDenied() throws Exception {
            when(paymentService.getByRef("PAY-B")).thenReturn(paymentFrom(ACCOUNT_OF_B));

            mvc.perform(as(get("/api/payments/ref/{ref}", "PAY-B"), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a customer cannot pay from another customer's account")
        void foreignPayerAccountDenied() throws Exception {
            mvc.perform(as(post("/api/payments"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payerAccountId\":%d,\"amount\":50.00,\"paymentType\":\"INTERNAL\"}"
                                    .formatted(ACCOUNT_OF_B)))
                    .andExpect(status().isForbidden());

            // Refused before the payment is created, so no money moves.
            verify(paymentService, never()).createPayment(any());
        }

        @Test
        @DisplayName("a customer cannot cancel another customer's payment")
        void foreignCancelDenied() throws Exception {
            when(paymentService.getById(5L)).thenReturn(paymentFrom(ACCOUNT_OF_B));

            mvc.perform(as(put("/api/payments/{id}/cancel", 5L), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(paymentService, never()).cancel(anyLong());
        }
    }

    @Nested
    @DisplayName("fail closed")
    class FailClosed {

        @Test
        @DisplayName("a request carrying no gateway identity is rejected")
        void noIdentityRejected() throws Exception {
            mvc.perform(get("/api/payments/account/{id}", ACCOUNT_OF_A))
                    .andExpect(status().isUnauthorized());

            verify(paymentService, never()).getByPayerAccount(anyLong());
        }
    }
}
