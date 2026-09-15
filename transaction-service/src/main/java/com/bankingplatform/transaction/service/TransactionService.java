package com.bankingplatform.transaction.service;

import com.bankingplatform.transaction.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TransactionService {

    TransactionResponse deposit(DepositRequest request);

    TransactionResponse withdraw(WithdrawRequest request);

    TransferResponse transfer(TransferRequest request);

    TransactionResponse getByRef(String ref);

    Page<TransactionResponse> getByAccountId(Long accountId, Pageable pageable);
}
