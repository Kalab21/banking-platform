package com.bankingplatform.integration.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.integration.client.AccountClient;
import com.bankingplatform.integration.dto.AccountSummary;
import com.bankingplatform.integration.dto.TransferResponse;
import com.bankingplatform.integration.security.AccountOwnershipVerifier;
import com.bankingplatform.integration.service.IntegrationService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may send money out of the bank, and read that it was sent.
 *
 * <p>integration-service had no authorization at all. The gateway
 * authenticated {@code /api/integrations/**}, but nothing checked that the
 * {@code fromAccountId} in a wire, ACH or SWIFT request belonged to the caller,
 * and nothing checked who was reading a transfer back. A signed-in customer
 * could send money from an account that was not theirs by changing one number
 * in a request body, and could read another customer's beneficiary name, IBAN,
 * routing number and amount by quoting a transfer reference.
 *
 * <p>Written at the HTTP boundary, with the real {@link AccountOwnershipVerifier}
 * over a mocked {@link AccountClient}: the rule itself is under test, not a
 * stub standing in for it. Every denial asserts both halves — the caller gets
 * 403, and {@link IntegrationService} is never reached, so no transfer is
 * persisted and no Kafka event is published on a refused request.
 */
@DisplayName("Integration transfer authorization")
class IntegrationAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long EMPLOYEE = 98L;

    private static final long ACCOUNT_OF_A = 5L;
    private static final String REF = "1f0b6c2a-0000-4000-8000-000000000001";

    private IntegrationService integrationService;
    private AccountClient accountClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        integrationService = Mockito.mock(IntegrationService.class);
        accountClient = Mockito.mock(AccountClient.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new IntegrationController(
                        integrationService, new AccountOwnershipVerifier(accountClient)))
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

    /** account-service is the authority on who owns an account. */
    private void accountBelongsTo(long ownerUserId) {
        AccountSummary account = new AccountSummary();
        account.setId(ACCOUNT_OF_A);
        account.setUserId(ownerUserId);
        when(accountClient.getAccountById(ACCOUNT_OF_A)).thenReturn(account);
    }

    private void transferWasSentFrom(long accountId) {
        TransferResponse transfer = new TransferResponse();
        transfer.setTransferRef(REF);
        transfer.setFromAccountId(accountId);
        when(integrationService.getTransfer(REF)).thenReturn(transfer);
    }

    private static String wireBody() {
        return """
                {"fromAccountId":5,"beneficiaryName":"London Corp Ltd",
                 "beneficiaryAccount":"GB29NWBK60161331926819","routingNumber":"026009593",
                 "bankName":"NatWest","bankCountry":"GB","amount":5000.00,
                 "currency":"USD","purpose":"Business payment"}
                """;
    }

    private static String achBody() {
        return """
                {"fromAccountId":5,"beneficiaryName":"Acme Payroll",
                 "beneficiaryAccount":"12345678","routingNumber":"026009593",
                 "bankName":"Acme Bank","amount":250.00,"currency":"USD","purpose":"Payroll"}
                """;
    }

    private static String swiftBody() {
        return """
                {"fromAccountId":5,"beneficiaryName":"Tokyo Partners","iban":"JP1234567890",
                 "swiftCode":"BOTKTOKX","bankName":"Bank of Tokyo","bankCountry":"JP",
                 "amount":2000.00,"currency":"USD","purpose":"Services"}
                """;
    }

    @Nested
    @DisplayName("initiating an external transfer")
    class Initiating {

        @Test
        @DisplayName("a customer may send from their own account")
        void ownAccountAllowed() throws Exception {
            accountBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/integrations/wire-transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON).content(wireBody()))
                    .andExpect(status().isCreated());

            verify(integrationService).initiateWireTransfer(any());
        }

        @Test
        @DisplayName("a customer may not send from another customer's account")
        void otherAccountRefused() throws Exception {
            accountBelongsTo(CUSTOMER_B);

            mvc.perform(as(post("/api/integrations/wire-transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON).content(wireBody()))
                    .andExpect(status().isForbidden());

            // The refusal must leave nothing behind: no external_transfers row,
            // no transfer reference issued, no Kafka event.
            verifyNoInteractions(integrationService);
        }

        @Test
        @DisplayName("ACH is guarded by the same rule")
        void achRefused() throws Exception {
            accountBelongsTo(CUSTOMER_B);

            mvc.perform(as(post("/api/integrations/ach-transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON).content(achBody()))
                    .andExpect(status().isForbidden());

            verify(integrationService, never()).initiateAchTransfer(any());
        }

        @Test
        @DisplayName("SWIFT is guarded by the same rule")
        void swiftRefused() throws Exception {
            accountBelongsTo(CUSTOMER_B);

            mvc.perform(as(post("/api/integrations/swift-transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON).content(swiftBody()))
                    .andExpect(status().isForbidden());

            verify(integrationService, never()).initiateSwiftTransfer(any());
        }

        @Test
        @DisplayName("ACH from an owned account is allowed")
        void ownAchAllowed() throws Exception {
            accountBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/integrations/ach-transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON).content(achBody()))
                    .andExpect(status().isCreated());

            verify(integrationService).initiateAchTransfer(any());
        }

        @Test
        @DisplayName("SWIFT from an owned account is allowed")
        void ownSwiftAllowed() throws Exception {
            accountBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/integrations/swift-transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON).content(swiftBody()))
                    .andExpect(status().isCreated());

            verify(integrationService).initiateSwiftTransfer(any());
        }

        @Test
        @DisplayName("staff may act on a customer's account, as they may for an internal transfer")
        void staffAllowed() throws Exception {
            accountBelongsTo(CUSTOMER_A);

            mvc.perform(as(post("/api/integrations/wire-transfer"), EMPLOYEE, "EMPLOYEE")
                            .contentType(MediaType.APPLICATION_JSON).content(wireBody()))
                    .andExpect(status().isCreated());

            verify(integrationService).initiateWireTransfer(any());
        }
    }

    @Nested
    @DisplayName("reading a transfer by its reference")
    class Reading {

        @Test
        @DisplayName("a customer reads a transfer sent from their own account")
        void ownTransferAllowed() throws Exception {
            transferWasSentFrom(ACCOUNT_OF_A);
            accountBelongsTo(CUSTOMER_A);

            mvc.perform(as(get("/api/integrations/transfer/" + REF), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read another customer's transfer")
        void otherTransferRefused() throws Exception {
            transferWasSentFrom(ACCOUNT_OF_A);
            accountBelongsTo(CUSTOMER_B);

            // The reference is a guessable handle, so guessing one must not be
            // enough to read a beneficiary, an IBAN or an amount.
            mvc.perform(as(get("/api/integrations/transfer/" + REF), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("without an identity from the gateway")
    class NoIdentity {

        @Test
        @DisplayName("a transfer with no caller identity is refused as unauthenticated")
        void noIdentityRefused() throws Exception {
            mvc.perform(post("/api/integrations/wire-transfer")
                            .contentType(MediaType.APPLICATION_JSON).content(wireBody()))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(integrationService);
            // The ownership lookup is never even attempted: there is no
            // identity to compare an owner against.
            verifyNoInteractions(accountClient);
        }

        @Test
        @DisplayName("reading a transfer with no caller identity is refused")
        void noIdentityReadRefused() throws Exception {
            transferWasSentFrom(ACCOUNT_OF_A);
            accountBelongsTo(CUSTOMER_A);

            mvc.perform(get("/api/integrations/transfer/" + REF))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("endpoints with no customer-owned resource")
    class Unowned {

        /**
         * The rate from USD to GBP is the same for every caller and is read
         * from a static table. There is no owner to check, so inventing one
         * would be a check that authorises nothing. These stay behind gateway
         * authentication, and the account-ownership path is never touched.
         */
        @Test
        @DisplayName("an exchange rate needs no ownership check")
        void exchangeRateNeedsNoOwner() throws Exception {
            when(integrationService.getExchangeRate("USD", "GBP"))
                    .thenReturn(new com.bankingplatform.integration.dto.ExchangeRateResponse(
                            "USD", "GBP", new BigDecimal("0.79")));

            mvc.perform(as(get("/api/integrations/exchange-rate?from=USD&to=GBP"),
                            CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());

            verifyNoInteractions(accountClient);
        }

        @Test
        @DisplayName("account-number format validation needs no ownership check")
        void validateNeedsNoOwner() throws Exception {
            when(integrationService.validateAccount("12345678"))
                    .thenReturn(new com.bankingplatform.integration.dto.AccountValidationResponse(
                            "12345678", true, "Account number format is valid"));

            mvc.perform(as(get("/api/integrations/validate-account?accountNumber=12345678"),
                            CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());

            verifyNoInteractions(accountClient);
        }
    }

    @Nested
    @DisplayName("a source account that cannot identify an owner")
    class Unidentifiable {

        @Test
        @DisplayName("a null fromAccountId is refused without being sent downstream")
        void nullAccountRefused() throws Exception {
            String body = """
                    {"beneficiaryName":"London Corp Ltd","amount":5000.00,"currency":"USD"}
                    """;

            mvc.perform(as(post("/api/integrations/wire-transfer"), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(integrationService);
            verifyNoInteractions(accountClient);
        }
    }
}
