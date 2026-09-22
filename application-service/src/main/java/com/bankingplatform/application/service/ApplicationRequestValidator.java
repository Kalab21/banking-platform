package com.bankingplatform.application.service;

import com.bankingplatform.application.dto.CreateApplicationRequest;
import com.bankingplatform.application.exception.InvalidApplicationRequestException;
import com.bankingplatform.application.model.ApplicationType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * What each product requires of an application, and what it refuses.
 *
 * <p>Bean Validation on the request object can only state rules that hold for
 * every product at once, which for six products with different questions means
 * almost nothing: a mortgage with no property value and a credit-card
 * application with a self-chosen credit limit were both accepted.
 *
 * <p>Two kinds of rule live here. Required fields are what the demo
 * underwriting policy needs in order to decide at all — income and existing
 * debt for anything on credit, plus an asset value where the lending is
 * secured against one. Refused fields are the ones the product has no concept
 * of: a checking account is not a request for money, and a card applicant does
 * not state an amount or a term because Northbank sets the limit.
 *
 * <p>Refusing rather than ignoring is deliberate. An API that quietly drops a
 * field the caller sent is how an account came to be opened holding the
 * amount someone had typed into an application form.
 */
@Component
public class ApplicationRequestValidator {

    public void validate(CreateApplicationRequest request) {
        ApplicationType type = request.getApplicationType();
        if (type == null) {
            // Bean Validation has already refused this; belt and braces for
            // direct calls from tests and from other services.
            throw new InvalidApplicationRequestException("applicationType is required");
        }

        switch (type) {
            case CHECKING_ACCOUNT, SAVINGS_ACCOUNT -> validateDepositAccount(request);
            case CREDIT_CARD -> validateCreditCard(request);
            case PERSONAL_LOAN -> validatePersonalLoan(request);
            case AUTO_LOAN -> validateSecuredLoan(request, "vehicle");
            case MORTGAGE -> validateSecuredLoan(request, "property");
        }
    }

    /**
     * A deposit account asks for nothing and is funded afterwards, so an
     * amount or a term on one is a misunderstanding worth saying out loud.
     */
    private void validateDepositAccount(CreateApplicationRequest request) {
        refuse(request.getRequestedAmount() != null,
                "requestedAmount does not apply to a deposit account: an account opens empty "
                        + "and is funded by a deposit");
        refuse(request.getTermMonths() != null,
                "termMonths does not apply to a deposit account");
    }

    private void validateCreditCard(CreateApplicationRequest request) {
        refuse(request.getRequestedAmount() != null,
                "requestedAmount does not apply to a credit card: Northbank sets the credit limit");
        refuse(request.getTermMonths() != null,
                "termMonths does not apply to a credit card");
        requireAffordability(request);
    }

    private void validatePersonalLoan(CreateApplicationRequest request) {
        requirePositive(request.getRequestedAmount(), "requestedAmount");
        requireTerm(request);
        require(hasText(request.getPurpose()), "purpose is required for a personal loan");
        requireAffordability(request);
    }

    /**
     * Auto and mortgage lending is secured on something, so the policy needs
     * that thing's value to work out how much of it Northbank would be
     * lending against.
     */
    private void validateSecuredLoan(CreateApplicationRequest request, String asset) {
        requirePositive(request.getRequestedAmount(), "requestedAmount");
        requireTerm(request);
        requireAffordability(request);
        requirePositive(request.getAssetValue(), "assetValue (the " + asset + "'s value)");

        BigDecimal down = request.getDownPayment();
        if (down != null) {
            refuse(down.signum() < 0, "downPayment cannot be negative");
            refuse(down.compareTo(request.getAssetValue()) >= 0,
                    "downPayment cannot be the whole " + asset + "'s value or more");
        }
    }

    private void requireAffordability(CreateApplicationRequest request) {
        requirePositive(request.getAnnualIncome(), "annualIncome");
        BigDecimal debt = request.getMonthlyDebtObligations();
        require(debt != null, "monthlyDebtObligations is required");
        // Zero is a real answer — someone may have no existing commitments —
        // so this is a sign check rather than a positive check.
        refuse(debt.signum() < 0, "monthlyDebtObligations cannot be negative");
    }

    private void requireTerm(CreateApplicationRequest request) {
        require(request.getTermMonths() != null, "termMonths is required");
    }

    private void requirePositive(BigDecimal value, String field) {
        require(value != null, field + " is required");
        refuse(value.signum() <= 0, field + " must be greater than zero");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidApplicationRequestException(message);
        }
    }

    private void refuse(boolean condition, String message) {
        if (condition) {
            throw new InvalidApplicationRequestException(message);
        }
    }
}
