package com.bankingplatform.loan.exception;

/**
 * The account holds a different currency from the loan.
 *
 * <p>Nothing converts, so the same number would be taken or paid in two
 * currencies. An {@link IllegalStateException}, so the idempotency classifier
 * already counts it as having moved no money: it is raised before any debit.
 */
public class AccountCurrencyMismatchException extends IllegalStateException {
    public AccountCurrencyMismatchException(String message) { super(message); }
}
