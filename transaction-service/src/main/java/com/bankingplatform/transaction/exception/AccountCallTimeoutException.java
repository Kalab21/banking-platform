package com.bankingplatform.transaction.exception;

/**
 * The call to {@code account-service} was abandoned before a response arrived.
 *
 * <p>Distinct from an open circuit on purpose. When the circuit is open the
 * request never leaves this service, so the caller can safely be told that no
 * money moved. A timeout carries no such guarantee: the debit may have been
 * applied and only the response lost. Conflating the two would let the console
 * tell a customer their transfer did not happen when it did.
 */
public class AccountCallTimeoutException extends RuntimeException {

    public AccountCallTimeoutException(String message) {
        super(message);
    }
}
