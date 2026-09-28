package com.bankingplatform.integration.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.integration.client.AccountClient;
import com.bankingplatform.integration.dto.AccountSummary;
import com.bankingplatform.integration.exception.GlobalExceptionHandler;
import com.bankingplatform.integration.security.AccountOwnershipVerifier;
import com.bankingplatform.integration.service.IntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * External transfer requests are validated before anything is recorded.
 *
 * <p>There were no constraints at all: a negative amount, a fraction of a cent
 * or a transfer naming no source account was persisted and announced on Kafka.
 */
@DisplayName("External transfer request validation")
class IntegrationValidationTest {

    private IntegrationService integrationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        integrationService = Mockito.mock(IntegrationService.class);
        AccountClient accountClient = Mockito.mock(AccountClient.class);
        AccountSummary account = new AccountSummary();
        account.setId(5L);
        account.setUserId(10L);
        when(accountClient.getAccountById(5L)).thenReturn(account);

        mvc = MockMvcBuilders
                .standaloneSetup(new IntegrationController(
                        integrationService, new AccountOwnershipVerifier(accountClient)))
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    private void wire(String amount) throws Exception {
        mvc.perform(post("/api/integrations/wire-transfer")
                        .header(CallerIdentityHeaders.USER_ID, "10")
                        .header(CallerIdentityHeaders.USERNAME, "user10")
                        .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":5,"beneficiaryName":"London Corp Ltd",
                                 "beneficiaryAccount":"GB29EXMP60161331926819","bankName":"Example Bank",
                                 "bankCountry":"GB","amount":%s,"currency":"USD","purpose":"Invoice"}
                                """.formatted(amount)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "an amount of {0} is refused")
    @ValueSource(strings = {"-100.00", "0", "0.001", "null"})
    void badAmountsAreRefused(String amount) throws Exception {
        wire(amount);
        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("an ACH transfer needs a nine-digit routing number")
    void achNeedsARoutingNumber() throws Exception {
        mvc.perform(post("/api/integrations/ach-transfer")
                        .header(CallerIdentityHeaders.USER_ID, "10")
                        .header(CallerIdentityHeaders.USERNAME, "user10")
                        .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":5,"beneficiaryName":"Acme Payroll",
                                 "beneficiaryAccount":"12345678","routingNumber":"12AB",
                                 "amount":250.00,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(integrationService);
    }

    @Test
    @DisplayName("a transfer must name its source account")
    void sourceAccountIsRequired() throws Exception {
        mvc.perform(post("/api/integrations/swift-transfer")
                        .header(CallerIdentityHeaders.USER_ID, "10")
                        .header(CallerIdentityHeaders.USERNAME, "user10")
                        .header(CallerIdentityHeaders.USER_ROLE, "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"beneficiaryName":"Tokyo Partners","iban":"JP1234567890",
                                 "swiftCode":"EXMPJPJT","amount":2000.00,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(integrationService);
    }
}
