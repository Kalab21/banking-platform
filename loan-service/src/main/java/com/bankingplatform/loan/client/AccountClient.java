package com.bankingplatform.loan.client;

import com.bankingplatform.loan.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.util.Map;

@FeignClient(name = "account-service", configuration = FeignConfig.class)
public interface AccountClient {

    /**
     * Applies a balance movement, at most once per key.
     *
     * <p>The key names the movement, not the request. A retry of the same
     * movement carries the same key and applies nothing the second time; a
     * genuinely new movement gets a new one. It is derived from the loan
     * reference the movement belongs to, because that reference is generated
     * once and survives a retry.
     */
    @PutMapping("/internal/accounts/{id}/balance")
    void updateBalance(@PathVariable Long id,
                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                       @RequestBody Map<String, Object> request);

    default void credit(Long accountId, String idempotencyKey, BigDecimal amount, String description) {
        updateBalance(accountId, idempotencyKey,
                Map.of("amount", amount, "operation", "CREDIT", "description", description));
    }

    default void debit(Long accountId, String idempotencyKey, BigDecimal amount, String description) {
        updateBalance(accountId, idempotencyKey,
                Map.of("amount", amount, "operation", "DEBIT", "description", description));
    }
}
