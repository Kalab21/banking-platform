package com.bankingplatform.creditcard.exception;

public class InsufficientCreditException extends RuntimeException {
    public InsufficientCreditException(String message) { super(message); }
}
