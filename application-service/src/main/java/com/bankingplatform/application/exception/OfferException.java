package com.bankingplatform.application.exception;

/** An offer was acted on in a way the lifecycle does not allow. */
public class OfferException extends RuntimeException {
    public OfferException(String message) {
        super(message);
    }
}
