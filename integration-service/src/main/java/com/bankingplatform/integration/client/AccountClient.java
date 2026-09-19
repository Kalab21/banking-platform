package com.bankingplatform.integration.client;

import com.bankingplatform.integration.config.FeignConfig;
import com.bankingplatform.integration.dto.AccountSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Reads an account so its owner can be established.
 *
 * <p>Deliberately read-only. An external transfer in this platform is
 * recorded, not settled — no balance is debited — so this service has no
 * business calling the internal balance endpoint, and cannot.
 *
 * <p>No fallback and no circuit breaker. The only call is the one that decides
 * whether a request is authorised, and there is no safe substitute answer to
 * return when it cannot be made: defaulting to "allowed" would open the hole
 * this closes, and defaulting to "denied" is what an ordinary propagated
 * failure already produces.
 */
@FeignClient(name = "account-service", configuration = FeignConfig.class)
public interface AccountClient {

    @GetMapping("/api/accounts/{id}")
    AccountSummary getAccountById(@PathVariable Long id);
}
