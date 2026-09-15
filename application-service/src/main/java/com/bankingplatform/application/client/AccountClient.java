package com.bankingplatform.application.client;

import com.bankingplatform.application.config.FeignConfig;
import com.bankingplatform.application.dto.AccountResponse;
import com.bankingplatform.application.dto.CreateAccountRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "account-service", configuration = FeignConfig.class)
public interface AccountClient {

    @PostMapping("/api/accounts")
    AccountResponse createAccount(@RequestBody CreateAccountRequest request);
}
