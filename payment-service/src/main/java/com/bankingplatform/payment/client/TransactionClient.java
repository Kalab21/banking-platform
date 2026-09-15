package com.bankingplatform.payment.client;

import com.bankingplatform.payment.config.FeignConfig;
import com.bankingplatform.payment.dto.TransferRequest;
import com.bankingplatform.payment.dto.TransferResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "transaction-service", configuration = FeignConfig.class)
public interface TransactionClient {

    @PostMapping("/api/transactions/transfer")
    TransferResponse transfer(@RequestBody TransferRequest request);
}
