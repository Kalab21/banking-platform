package com.bankingplatform.payment.client;

import com.bankingplatform.payment.config.FeignConfig;
import com.bankingplatform.payment.dto.TransferRequest;
import com.bankingplatform.payment.dto.TransactionResponse;
import com.bankingplatform.payment.dto.TransferResponse;
import com.bankingplatform.payment.dto.WithdrawRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "transaction-service", configuration = FeignConfig.class)
public interface TransactionClient {

    /**
     * @param idempotencyKey names the payment this transfer settles. Required by
     *        the endpoint, and the reason a payment that is picked up twice by
     *        the scheduler moves money once.
     */
    @PostMapping("/api/transactions/transfer")
    TransferResponse transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                              @RequestBody TransferRequest request);

    /**
     * Money leaving the bank: a bill, ACH, wire or SWIFT payment. The rail
     * itself is simulated, but the payer's balance is not.
     */
    @PostMapping("/api/transactions/withdraw")
    TransactionResponse withdraw(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @RequestBody WithdrawRequest request);
}
