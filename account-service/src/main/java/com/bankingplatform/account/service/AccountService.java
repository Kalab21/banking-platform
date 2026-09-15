package com.bankingplatform.account.service;

import com.bankingplatform.account.dto.*;
import com.bankingplatform.account.model.AccountStatus;

import java.util.List;

public interface AccountService {

    AccountResponse createAccount(CreateAccountRequest request);

    AccountResponse getAccountById(Long id);

    AccountResponse getAccountByNumber(String accountNumber);

    List<AccountResponse> getAccountsByUserId(Long userId);

    List<AccountResponse> getActiveAccountsByUserId(Long userId);

    AccountResponse updateBalance(Long id, BalanceUpdateRequest request);

    AccountResponse updateStatus(Long id, AccountStatus status);

    AccountResponse updateOverdraftLimit(Long id, UpdateOverdraftRequest request);
}
