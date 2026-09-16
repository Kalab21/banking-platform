package com.bankingplatform.common.security;

/**
 * The caller is known but is not allowed to act on this resource.
 *
 * <p>Deliberately carries no detail about the resource. Saying "account 42
 * belongs to someone else" confirms that account 42 exists, which turns a
 * rejected request into an enumeration oracle.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
