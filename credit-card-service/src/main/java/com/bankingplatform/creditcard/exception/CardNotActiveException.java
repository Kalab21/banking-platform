package com.bankingplatform.creditcard.exception;

public class CardNotActiveException extends RuntimeException {
    public CardNotActiveException(String message) { super(message); }
}
