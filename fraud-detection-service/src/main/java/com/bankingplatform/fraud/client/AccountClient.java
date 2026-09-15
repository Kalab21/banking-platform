package com.bankingplatform.fraud.client;

import com.bankingplatform.fraud.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "account-service", configuration = FeignConfig.class)
public interface AccountClient {

    @PutMapping("/api/accounts/{id}/status")
    void updateStatus(@PathVariable Long id, @RequestParam String status);
}
