package com.bankingplatform.transaction.client;

import com.bankingplatform.transaction.dto.AccountResponse;
import com.bankingplatform.transaction.exception.AccountCallTimeoutException;
import com.bankingplatform.transaction.dto.BalanceUpdateRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * Restores the original failure after the circuit breaker has wrapped it.
 *
 * <p>Enabling the breaker put {@code FeignCircuitBreakerInvocationHandler}
 * between this service and {@code account-service}. With no fallback declared,
 * every failure — including a perfectly correct "insufficient funds" 422 —
 * came back as {@code NoFallbackAvailableException}, whose {@code getCause()}
 * is null in Spring Cloud 3.1.2. The handler upstream could no longer tell a
 * refused debit from a dead dependency, so a customer overdrawing their account
 * was told the platform was broken.
 *
 * <p>A fallback factory is the supported way back to the truth: Spring Cloud
 * hands it the causing {@link Throwable}, and rethrowing it lets the existing
 * {@code FeignException} handlers map the real status again.
 *
 * <p>This deliberately does <em>not</em> return a substitute value. There is no
 * safe stand-in for "did the debit apply?" — inventing one would risk
 * reporting a transfer as successful when no money moved.
 */
@Component
@Slf4j
public class AccountClientFallbackFactory implements FallbackFactory<AccountClient> {

    @Override
    public AccountClient create(Throwable cause) {
        return new AccountClient() {

            @Override
            public AccountResponse getAccountById(Long id) {
                throw rethrow(cause);
            }

            @Override
            public AccountResponse updateBalance(Long id, BalanceUpdateRequest request) {
                throw rethrow(cause);
            }
        };
    }

    private RuntimeException rethrow(Throwable cause) {
        if (cause instanceof CallNotPermittedException open) {
            // The circuit is open, so the call never left this service and no
            // money moved. Surfaced as-is for the 503 handler.
            log.warn("Circuit open for account-service: {}", open.getMessage());
            return open;
        }
        if (cause instanceof java.util.concurrent.TimeoutException timeout) {
            // The call was abandoned, not refused. Unlike an open circuit, the
            // request may already have reached account-service and applied,
            // so the outcome is genuinely unknown and must be reported as such.
            log.error("account-service call timed out; outcome unknown", timeout);
            return new AccountCallTimeoutException(
                    "The account service did not respond in time. The outcome of this request is unknown.");
        }
        if (cause instanceof RuntimeException runtime) {
            // FeignException and friends: let the existing handlers map the
            // status they always did.
            return runtime;
        }
        log.error("Unexpected fallback cause type: {}",
                cause == null ? "null" : cause.getClass().getName(), cause);
        return new IllegalStateException("account-service call failed", cause);
    }
}
