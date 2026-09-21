package com.bankingplatform.transaction.client;

import com.bankingplatform.transaction.config.FeignConfig;
import com.bankingplatform.transaction.dto.AccountResponse;
import com.bankingplatform.transaction.dto.BalanceUpdateRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "account-service",
        configuration = FeignConfig.class,
        // Rethrows the original exception so the circuit breaker's wrapper does
        // not flatten a business rejection into a generic failure. See
        // AccountClientFallbackFactory.
        fallbackFactory = AccountClientFallbackFactory.class)
public interface AccountClient {

    @GetMapping("/api/accounts/{id}")
    AccountResponse getAccountById(@PathVariable Long id);

    /**
     * Applies a balance movement, at most once per key.
     *
     * <p>The key names the movement, not the request. A retry of the same
     * movement carries the same key and applies nothing the second time; a
     * genuinely new movement gets a new one. It is derived from the
     * transaction or payment reference this movement belongs to, because
     * that reference is generated once and survives a retry.
     */
    @PutMapping("/internal/accounts/{id}/balance")
    AccountResponse updateBalance(@PathVariable Long id,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @RequestBody BalanceUpdateRequest request);
}
