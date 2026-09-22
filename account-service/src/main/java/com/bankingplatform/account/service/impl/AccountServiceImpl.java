package com.bankingplatform.account.service.impl;

import com.bankingplatform.common.security.CallerContext;
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
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {

    private static final BigDecimal OVERDRAFT_FEE = new BigDecimal("35.00");

    /**
     * Source of the random part of an account number.
     *
     * <p>{@code java.util.Random} was seeded from the clock and its sequence is
     * recoverable from a couple of outputs, so numbers issued near each other
     * were guessable from one another.
     *
     * <p>Guessing one grants nothing by itself: every lookup by account number
     * is authorised against the caller, and the boundary tests cover that. But
     * an identifier a customer hands to a third party should not be derivable
     * from the one issued just before it.
     *
     * <p>Static and final. {@link SecureRandom} is thread-safe, and building one
     * per call would re-seed from the entropy pool on every account opening.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
                .balance(BigDecimal.ZERO)
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .overdraftLimit(request.getOverdraftLimit())
                .build();

        Account saved = accountRepository.save(account);

        audit("ACCOUNT", saved.getId(), "CREATE",
                "Account created: type=" + saved.getAccountType() + ", number=" + saved.getAccountNumber());

        // No account number: the event carries who owns the account and what
        // kind it is, which is all either consumer reads. See AccountCreated.
        eventProducer.publishAccountCreated(
                saved.getId(), saved.getUserId(), saved.getAccountType().name());

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

    /**
     * Applies a credit or a debit to one account.
     *
     * <p>The account is loaded {@code FOR UPDATE}. Everything from here to the
     * commit — reading the balance, deciding whether there is enough, writing
     * the new figure — happens with the row locked, so two concurrent debits
     * serialise instead of interleaving and the second one's sufficiency check
     * sees the balance the first one left.
     */
    @Override
    @Transactional
    public AccountResponse updateBalance(Long id, BalanceUpdateRequest request) {
        Account account = findByIdForUpdate(id);

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
        audit("ACCOUNT", id, request.getOperation(), "Amount: " + amount);
        eventProducer.publishBalanceUpdated(saved.getId(), saved.getUserId(),
                saved.getBalance(), request.getOperation());

        return accountMapper.toResponse(saved);
    }

    /**
     * Freezes, closes or reactivates an account.
     *
     * <p>Locked for the same reason as a balance change: a debit that pushes
     * the account into overdraft writes the status too, and an unlocked
     * read-modify-write here could overwrite that with a stale value.
     */
    @Override
    @Transactional
    public AccountResponse updateStatus(Long id, AccountStatus status) {
        Account account = findByIdForUpdate(id);
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountStatusException("Cannot reopen a closed account");
        }
        account.setStatus(status);
        audit("ACCOUNT", id, "STATUS_CHANGE", "New status: " + status);
        return accountMapper.toResponse(accountRepository.save(account));
    }

    /**
     * Sets the overdraft limit.
     *
     * <p>Locked because the limit is an input to the sufficiency check, so
     * changing it concurrently with a debit must be ordered against that debit
     * rather than racing it.
     */
    @Override
    @Transactional
    public AccountResponse updateOverdraftLimit(Long id, UpdateOverdraftRequest request) {
        Account account = findByIdForUpdate(id);
        account.setOverdraftLimit(request.getOverdraftLimit());
        audit("ACCOUNT", id, "OVERDRAFT_UPDATE", "New limit: " + request.getOverdraftLimit());
        return accountMapper.toResponse(accountRepository.save(account));
    }

    /** Unlocked read, for the query paths. */
    private Account findById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + id));
    }

    /**
     * Locked read, for every path that changes the row it just read.
     *
     * <p>Must be called inside a transaction — the lock is released at commit,
     * and outside one it would be released immediately and guarantee nothing.
     */
    private Account findByIdForUpdate(Long id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + id));
    }

    private String generateAccountNumber() {
        String prefix = "BA";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String candidate = prefix + timestamp + randomSuffix();
        while (accountRepository.existsByAccountNumber(candidate)) {
            candidate = prefix + timestamp + randomSuffix();
        }
        return candidate;
    }

    /** Six digits, zero padded — the shape the account number has always had. */
    private String randomSuffix() {
        return String.format("%06d", SECURE_RANDOM.nextInt(999999));
    }

    /**
     * Records who did this, not only what was done.
     *
     * <p>The actor comes from the request rather than from an argument.
     * Several call sites used to pass the <em>subject</em> of the change --
     * the account holder, the applicant -- which reads correctly right up
     * until a member of staff acts on a customer's behalf, and then the audit
     * row names the customer as having done it themselves.
     *
     * <p>{@code actorType} is always set. A scheduled job or a Kafka listener
     * has no caller and is recorded as {@code SYSTEM}, so a null
     * {@code performedBy} beside it means "no user was involved" rather than
     * "the attribution was lost".
     */
    private void audit(String entityType, Long entityId, String action, String details) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .performedBy(CallerContext.userId().orElse(null))
                .actorType(CallerContext.actor())
                .details(details)
                .build());
    }
}
