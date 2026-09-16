package com.bankingplatform.common.security;

/**
 * No usable caller identity reached this service on a user-facing endpoint.
 *
 * <p>Treated as 401 rather than 403: the request never established who it is,
 * so the correct answer is "authenticate", not "you may not". In practice it
 * means the request did not come through the gateway, or the gateway forwarded
 * a malformed value.
 */
public class MissingCallerIdentityException extends RuntimeException {

    public MissingCallerIdentityException(String message) {
        super(message);
    }
}
