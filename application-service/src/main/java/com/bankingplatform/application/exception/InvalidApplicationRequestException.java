package com.bankingplatform.application.exception;

/**
 * The submitted application does not carry what its product requires.
 *
 * <p>Separate from {@link ApplicationException} in how it is answered: this is
 * a malformed request (400) rather than a conflict with the application's
 * current state (409).
 */
public class InvalidApplicationRequestException extends ApplicationException {

    public InvalidApplicationRequestException(String message) {
        super(message);
    }
}
