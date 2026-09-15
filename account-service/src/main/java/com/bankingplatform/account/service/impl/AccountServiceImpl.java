package com.bankingplatform.account.service.impl;

import com.bankingplatform.account.client.UserClient;
import com.bankingplatform.account.dto.*;
import com.bankingplatform.account.exception.AccountStatusException;
import com.bankingplatform.account.exception.InsufficientFundsException;
import com.bankingplatform.account.exception.ResourceNotFoundException;
import com.bankingplatform.account.kafka.producer.AccountEventProducer;
import com.bankingplatform.account.mapper.AccountMapper;
import com.bankingplatform.account.model.Account;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.model.AuditLog;
import com.bankingplatform.account.repository.AccountRepository;
import com.bankingplatform.account.repository.AuditLogRepository;
import com.bankingplatform.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {

    private static final BigDecimal OVERDRAFT_FEE = new BigDecimal("35.00");

    private final AccountRepository accountRepository;
    private final AuditLogRepository auditLogRepository;
    private final AccountMapper accountMapper;
    private final AccountEventProducer eventProducer;
    private final UserClient userClient;

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        userClient.getUserById(request.getUserId());

        Account account = Account.builder()
                .accountNumber(generateAccountNumber())
                .userId(request.getUserId())
                .accountType(request.getAccountType())
                .balance(request.getInitialDeposit())
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .overdraftLimit(request.getOverdraftLimit())
                .build();

        Account saved = accountRepository.save(account);

        audit("ACCOUNT", saved.getId(), "CREATE", request.getUserId(),
                "Account created: type=" + saved.getAccountType() + ", number=" + saved.getAccountNumber());

        eventProducer.publishAccountCreated(
                saved.getId(), saved.getUserId(),
                saved.getAccountNumber(), saved.getAccountType().name());

        return accountMapper.toResponse(saved);
    }

    @Override
    public AccountResponse getAccountById(Long id) {
        return accountMapper.toResponse(findById(id));
    }

    @Override
    public AccountResponse getAccountByNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(accountMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));
    }

    @Override
    public List<AccountResponse> getAccountsByUserId(Long userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(accountMapper::toResponse)
                .toList();
    }

    @Override
    public List<AccountResponse> getActiveAccountsByUserId(Long userId) {
        return accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE).stream()
                .map(accountMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public AccountResponse updateBalance(Long id, BalanceUpdateRequest request) {
        Account account = findById(id);

        if (account.getStatus() == AccountStatus.FROZEN || account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountStatusException("Cannot transact on a " + account.getStatus() + " account");
        }

        BigDecimal amount = request.getAmount();

        if ("CREDIT".equalsIgnoreCase(request.getOperation())) {
            account.setBalance(account.getBalance().add(amount));
            if (account.getStatus() == AccountStatus.OVERDRAWN
                    && account.getOverdraftBalance().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal repay = amount.min(account.getOverdraftBalance());
                account.setOverdraftBalance(account.getOverdraftBalance().subtract(repay));
                if (account.getOverdraftBalance().compareTo(BigDecimal.ZERO) == 0) {
                    account.setStatus(AccountStatus.ACTIVE);
                }
            }
        } else if ("DEBIT".equalsIgnoreCase(request.getOperation())) {
            BigDecimal available = account.getBalance().add(account.getOverdraftLimit())
                    .subtract(account.getOverdraftBalance());

            if (available.compareTo(amount) < 0) {
                throw new InsufficientFundsException(
                        "Insufficient funds. Available: " + available + ", Requested: " + amount);
            }

            if (account.getBalance().compareTo(amount) >= 0) {
                account.setBalance(account.getBalance().subtract(amount));
            } else {
                BigDecimal deficit = amount.subtract(account.getBalance());
                account.setBalance(BigDecimal.ZERO);
                account.setOverdraftBalance(account.getOverdraftBalance().add(deficit));
                account.setStatus(AccountStatus.OVERDRAWN);

                account.setBalance(account.getBalance().subtract(OVERDRAFT_FEE));
                eventProducer.publishOverdraftTriggered(account.getId(), account.getUserId(), deficit);
                log.warn("Overdraft triggered on account {} — deficit: {}, fee: {}", id, deficit, OVERDRAFT_FEE);
            }
        }

        Account saved = accountRepository.save(account);
        audit("ACCOUNT", id, request.getOperation(), null, "Amount: " + amount);
        eventProducer.publishBalanceUpdated(saved.getId(), saved.getUserId(),
                saved.getBalance(), request.getOperation());

        return accountMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public AccountResponse updateStatus(Long id, AccountStatus status) {
        Account account = findById(id);
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountStatusException("Cannot reopen a closed account");
        }
        account.setStatus(status);
        audit("ACCOUNT", id, "STATUS_CHANGE", null, "New status: " + status);
        return accountMapper.toResponse(accountRepository.save(account));
    }

    @Override
    @Transactional
    public AccountResponse updateOverdraftLimit(Long id, UpdateOverdraftRequest request) {
        Account account = findById(id);
        account.setOverdraftLimit(request.getOverdraftLimit());
        audit("ACCOUNT", id, "OVERDRAFT_UPDATE", null, "New limit: " + request.getOverdraftLimit());
        return accountMapper.toResponse(accountRepository.save(account));
    }

    private Account findById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + id));
    }

    private String generateAccountNumber() {
        String prefix = "BA";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String random = String.format("%06d", new Random().nextInt(999999));
        String candidate = prefix + timestamp + random;
        while (accountRepository.existsByAccountNumber(candidate)) {
            candidate = prefix + timestamp + String.format("%06d", new Random().nextInt(999999));
        }
        return candidate;
    }

    private void audit(String entityType, Long entityId, String action, Long performedBy, String details) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .performedBy(performedBy)
                .details(details)
                .build());
    }
}
