package com.bankingplatform.application.service;

import com.bankingplatform.application.dto.CreateApplicationRequest;
import com.bankingplatform.application.exception.InvalidApplicationRequestException;
import com.bankingplatform.application.model.ApplicationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each product asks of an applicant, and what it refuses to be told.
 *
 * <p>Before this existed, one set of annotations covered six products at once,
 * which meant it covered almost nothing: a mortgage with no property value was
 * accepted, and so was a credit-card application stating an amount and a term
 * that Northbank, not the customer, decides.
 */
@DisplayName("ApplicationRequestValidator")
class ApplicationRequestValidatorTest {

    private final ApplicationRequestValidator validator = new ApplicationRequestValidator();

    private static CreateApplicationRequest request(ApplicationType type) {
        CreateApplicationRequest request = new CreateApplicationRequest();
        request.setUserId(7L);
        request.setApplicationType(type);
        return request;
    }

    /** A complete, affordable application for something on credit. */
    private static CreateApplicationRequest creditRequest(ApplicationType type) {
        CreateApplicationRequest request = request(type);
        request.setAnnualIncome(new BigDecimal("90000.00"));
        request.setMonthlyDebtObligations(new BigDecimal("500.00"));
        return request;
    }

    private static CreateApplicationRequest loanRequest(ApplicationType type) {
        CreateApplicationRequest request = creditRequest(type);
        request.setRequestedAmount(new BigDecimal("15000.00"));
        request.setTermMonths(48);
        request.setPurpose("Kitchen refit");
        return request;
    }

    @Nested
    @DisplayName("deposit accounts")
    class DepositAccounts {

        @Test
        @DisplayName("need nothing beyond who is asking")
        void bareRequestIsEnough() {
            assertThatCode(() -> validator.validate(request(ApplicationType.CHECKING_ACCOUNT)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator.validate(request(ApplicationType.SAVINGS_ACCOUNT)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuse a requested amount, because an account is not a request for money")
        void refusesRequestedAmount() {
            CreateApplicationRequest request = request(ApplicationType.CHECKING_ACCOUNT);
            request.setRequestedAmount(new BigDecimal("500.00"));

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("requestedAmount")
                    .hasMessageContaining("opens empty");
        }

        @Test
        @DisplayName("refuse a term")
        void refusesTerm() {
            CreateApplicationRequest request = request(ApplicationType.SAVINGS_ACCOUNT);
            request.setTermMonths(12);

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("termMonths");
        }
    }

    @Nested
    @DisplayName("credit card")
    class CreditCard {

        @Test
        @DisplayName("needs income and existing debt, and nothing about limits")
        void affordabilityOnly() {
            assertThatCode(() -> validator.validate(creditRequest(ApplicationType.CREDIT_CARD)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a requested amount: Northbank sets the credit limit")
        void refusesRequestedAmount() {
            CreateApplicationRequest request = creditRequest(ApplicationType.CREDIT_CARD);
            request.setRequestedAmount(new BigDecimal("5000.00"));

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("credit limit");
        }

        @Test
        @DisplayName("requires an income")
        void requiresIncome() {
            CreateApplicationRequest request = request(ApplicationType.CREDIT_CARD);
            request.setMonthlyDebtObligations(BigDecimal.ZERO);

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("annualIncome");
        }

        @Test
        @DisplayName("accepts no existing debt, which is a real answer")
        void zeroDebtIsValid() {
            CreateApplicationRequest request = creditRequest(ApplicationType.CREDIT_CARD);
            request.setMonthlyDebtObligations(BigDecimal.ZERO);

            assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("distinguishes no answer from an answer of zero")
        void missingDebtIsNotZero() {
            CreateApplicationRequest request = creditRequest(ApplicationType.CREDIT_CARD);
            request.setMonthlyDebtObligations(null);

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("monthlyDebtObligations");
        }
    }

    @Nested
    @DisplayName("personal loan")
    class PersonalLoan {

        @Test
        @DisplayName("needs an amount, a term, a purpose and affordability")
        void completeRequest() {
            assertThatCode(() -> validator.validate(loanRequest(ApplicationType.PERSONAL_LOAN)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("requires a purpose")
        void requiresPurpose() {
            CreateApplicationRequest request = loanRequest(ApplicationType.PERSONAL_LOAN);
            request.setPurpose("   ");

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("purpose");
        }

        @Test
        @DisplayName("requires a term")
        void requiresTerm() {
            CreateApplicationRequest request = loanRequest(ApplicationType.PERSONAL_LOAN);
            request.setTermMonths(null);

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("termMonths");
        }

        @Test
        @DisplayName("refuses a non-positive amount")
        void refusesZeroAmount() {
            CreateApplicationRequest request = loanRequest(ApplicationType.PERSONAL_LOAN);
            request.setRequestedAmount(BigDecimal.ZERO);

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("greater than zero");
        }
    }

    @Nested
    @DisplayName("secured lending")
    class SecuredLending {

        private CreateApplicationRequest securedRequest(ApplicationType type) {
            CreateApplicationRequest request = loanRequest(type);
            request.setAssetValue(new BigDecimal("30000.00"));
            request.setDownPayment(new BigDecimal("5000.00"));
            return request;
        }

        @Test
        @DisplayName("an auto loan needs the vehicle's value")
        void autoNeedsVehicleValue() {
            assertThatCode(() -> validator.validate(securedRequest(ApplicationType.AUTO_LOAN)))
                    .doesNotThrowAnyException();

            CreateApplicationRequest without = loanRequest(ApplicationType.AUTO_LOAN);
            assertThatThrownBy(() -> validator.validate(without))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("vehicle");
        }

        @Test
        @DisplayName("a mortgage needs the property's value")
        void mortgageNeedsPropertyValue() {
            CreateApplicationRequest request = securedRequest(ApplicationType.MORTGAGE);
            request.setAssetValue(new BigDecimal("400000.00"));
            request.setRequestedAmount(new BigDecimal("320000.00"));
            request.setTermMonths(360);

            assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();

            CreateApplicationRequest without = loanRequest(ApplicationType.MORTGAGE);
            assertThatThrownBy(() -> validator.validate(without))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("property");
        }

        @Test
        @DisplayName("a down payment may be nothing, but not negative")
        void downPaymentSign() {
            CreateApplicationRequest none = securedRequest(ApplicationType.AUTO_LOAN);
            none.setDownPayment(BigDecimal.ZERO);
            assertThatCode(() -> validator.validate(none)).doesNotThrowAnyException();

            CreateApplicationRequest negative = securedRequest(ApplicationType.AUTO_LOAN);
            negative.setDownPayment(new BigDecimal("-1.00"));
            assertThatThrownBy(() -> validator.validate(negative))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("a down payment covering the whole asset leaves nothing to lend against")
        void downPaymentCannotCoverEverything() {
            CreateApplicationRequest request = securedRequest(ApplicationType.AUTO_LOAN);
            request.setDownPayment(new BigDecimal("30000.00"));

            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidApplicationRequestException.class)
                    .hasMessageContaining("whole");
        }
    }

    @Test
    @DisplayName("refuses an application with no product")
    void requiresType() {
        assertThatThrownBy(() -> validator.validate(new CreateApplicationRequest()))
                .isInstanceOf(InvalidApplicationRequestException.class)
                .hasMessageContaining("applicationType");
    }
}
