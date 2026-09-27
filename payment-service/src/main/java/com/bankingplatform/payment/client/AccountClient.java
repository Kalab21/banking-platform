package com.bankingplatform.payment.client;

import com.bankingplatform.payment.config.FeignConfig;
import com.bankingplatform.payment.dto.AccountResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "account-service", configuration = FeignConfig.class)
public interface AccountClient {

    @GetMapping("/api/accounts/{id}")
    AccountResponse getAccountById(@PathVariable Long id);

    /**
     * The same account read service-to-service, for work with no caller behind
     * it. A scheduled payment runs from a timer, and the public read above
     * refuses a request that carries no identity.
     */
    @GetMapping("/internal/accounts/{id}")
    AccountResponse getAccountInternal(@PathVariable Long id);
}
