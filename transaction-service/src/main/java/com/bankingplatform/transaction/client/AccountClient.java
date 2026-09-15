package com.bankingplatform.transaction.client;

import com.bankingplatform.transaction.config.FeignConfig;
import com.bankingplatform.transaction.dto.AccountResponse;
import com.bankingplatform.transaction.dto.BalanceUpdateRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "account-service", configuration = FeignConfig.class)
public interface AccountClient {

    @GetMapping("/api/accounts/{id}")
    AccountResponse getAccountById(@PathVariable Long id);

    @PutMapping("/api/accounts/{id}/balance")
    AccountResponse updateBalance(@PathVariable Long id, @RequestBody BalanceUpdateRequest request);
}
