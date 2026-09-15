package com.bankingplatform.loan.client;

import com.bankingplatform.loan.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.util.Map;

@FeignClient(name = "account-service", configuration = FeignConfig.class)
public interface AccountClient {

    @PutMapping("/api/accounts/{id}/balance")
    void updateBalance(@PathVariable Long id, @RequestBody Map<String, Object> request);

    default void credit(Long accountId, BigDecimal amount, String description) {
        updateBalance(accountId, Map.of("amount", amount, "operation", "CREDIT", "description", description));
    }

    default void debit(Long accountId, BigDecimal amount, String description) {
        updateBalance(accountId, Map.of("amount", amount, "operation", "DEBIT", "description", description));
    }
}
