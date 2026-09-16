package com.bankingplatform.common.security;

/** The headers the gateway sets after validating the JWT. */
public final class CallerIdentityHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USERNAME = "X-Username";
    public static final String USER_ROLE = "X-User-Role";

    private CallerIdentityHeaders() {
    }
}
