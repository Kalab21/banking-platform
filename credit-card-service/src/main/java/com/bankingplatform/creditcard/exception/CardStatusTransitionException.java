package com.bankingplatform.creditcard.exception;

/** A card status change the caller is not entitled to make. */
public class CardStatusTransitionException extends RuntimeException {
    public CardStatusTransitionException(String message) {
        super(message);
    }
}
