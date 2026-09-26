package com.bankingplatform.application.exception;

/**
 * An offer was acted on after it lapsed.
 *
 * <p>A separate type so the service can refuse the action without rolling back
 * the expiry it has just recorded: a transaction that rolls back on this would
 * leave the offer reading OFFERED in storage for ever.
 */
public class OfferExpiredException extends OfferException {
    public OfferExpiredException() {
        super("This offer has expired");
    }
}
