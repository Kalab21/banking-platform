package com.bankingplatform.transaction.service.impl;

import com.bankingplatform.transaction.client.AccountClient;
import com.bankingplatform.transaction.dto.*;
import com.bankingplatform.transaction.exception.ResourceNotFoundException;
import com.bankingplatform.transaction.exception.TransactionException;
import com.bankingplatform.transaction.kafka.producer.TransactionEventProducer;
import com.bankingplatform.transaction.mapper.TransactionMapper;
import com.bankingplatform.transaction.model.AuditLog;
import com.bankingplatform.transaction.model.Transaction;
import com.bankingplatform.transaction.model.TransactionType;
import com.bankingplatform.transaction.repository.AuditLogRepository;
import com.bankingplatform.transaction.repository.TransactionRepository;
import com.bankingplatform.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AuditLogRepository auditLogRepository;
    private final TransactionMapper transactionMapper;
    private final TransactionEventProducer eventProducer;
    private final AccountClient accountClient;

    @Override
    @Transactional
    public TransactionResponse deposit(DepositRequest request) {
        AccountResponse account = accountClient.updateBalance(
                request.getAccountId(),
                BalanceUpdateRequest.builder()
                        .amount(request.getAmount())
                        .operation("CREDIT")
                        .build()
        );

        String ref = generateRef();
        Transaction tx = Transaction.builder()
                .transactionRef(ref)
                .accountId(request.getAccountId())
                .type(TransactionType.DEPOSIT)
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : account.getCurrency())
                .balanceAfter(account.getBalance())
                .description(request.getDescription() != null ? request.getDescription() : "Deposit")
                .build();

        Transaction saved = transactionRepository.save(tx);
        audit("TRANSACTION", saved.getId(), "DEPOSIT", null,
                "Account " + request.getAccountId() + " amount=" + request.getAmount());
        eventProducer.publishTransactionCreated(saved.getId(), saved.getAccountId(),
                saved.getType().name(), saved.getAmount(), saved.getBalanceAfter(), ref);

        return transactionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public TransactionResponse withdraw(WithdrawRequest request) {
        AccountResponse account = accountClient.updateBalance(
                request.getAccountId(),
                BalanceUpdateRequest.builder()
                        .amount(request.getAmount())
                        .operation("DEBIT")
                        .build()
        );

        String ref = generateRef();
        Transaction tx = Transaction.builder()
                .transactionRef(ref)
                .accountId(request.getAccountId())
                .type(TransactionType.WITHDRAWAL)
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : account.getCurrency())
                .balanceAfter(account.getBalance())
                .description(request.getDescription() != null ? request.getDescription() : "Withdrawal")
                .build();

        Transaction saved = transactionRepository.save(tx);
        audit("TRANSACTION", saved.getId(), "WITHDRAWAL", null,
                "Account " + request.getAccountId() + " amount=" + request.getAmount());
        eventProducer.publishTransactionCreated(saved.getId(), saved.getAccountId(),
                saved.getType().name(), saved.getAmount(), saved.getBalanceAfter(), ref);

        return transactionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new TransactionException("Cannot transfer to the same account");
        }

        // Debit source
        AccountResponse fromAccount = accountClient.updateBalance(
                request.getFromAccountId(),
                BalanceUpdateRequest.builder()
                        .amount(request.getAmount())
                        .operation("DEBIT")
                        .build()
        );

        // Credit destination
        AccountResponse toAccount = accountClient.updateBalance(
                request.getToAccountId(),
                BalanceUpdateRequest.builder()
                        .amount(request.getAmount())
                        .operation("CREDIT")
                        .build()
        );

        String debitRef = generateRef();
        String creditRef = generateRef();
        String currency = request.getCurrency() != null ? request.getCurrency() : fromAccount.getCurrency();
        String desc = request.getDescription() != null ? request.getDescription() : "Transfer";

        Transaction debit = transactionRepository.save(Transaction.builder()
                .transactionRef(debitRef)
                .accountId(request.getFromAccountId())
                .type(TransactionType.TRANSFER_OUT)
                .amount(request.getAmount())
                .currency(currency)
                .balanceAfter(fromAccount.getBalance())
                .description(desc + " → account " + request.getToAccountId())
                .relatedTransactionRef(creditRef)
                .build());

        Transaction credit = transactionRepository.save(Transaction.builder()
                .transactionRef(creditRef)
                .accountId(request.getToAccountId())
                .type(TransactionType.TRANSFER_IN)
                .amount(request.getAmount())
                .currency(currency)
                .balanceAfter(toAccount.getBalance())
                .description(desc + " ← account " + request.getFromAccountId())
                .relatedTransactionRef(debitRef)
                .build());

        audit("TRANSACTION", debit.getId(), "TRANSFER_OUT", null,
                "From " + request.getFromAccountId() + " to " + request.getToAccountId() + " amount=" + request.getAmount());

        eventProducer.publishTransferCompleted(debitRef, creditRef,
                request.getFromAccountId(), request.getToAccountId(), request.getAmount());

        return TransferResponse.builder()
                .debit(transactionMapper.toResponse(debit))
                .credit(transactionMapper.toResponse(credit))
                .build();
    }

    @Override
    public TransactionResponse getByRef(String ref) {
        return transactionMapper.toResponse(
                transactionRepository.findByTransactionRef(ref)
                        .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + ref))
        );
    }

    @Override
    public Page<TransactionResponse> getByAccountId(Long accountId, Pageable pageable) {
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
                .map(transactionMapper::toResponse);
    }

    private String generateRef() {
        String ref = UUID.randomUUID().toString();
        while (transactionRepository.existsByTransactionRef(ref)) {
            ref = UUID.randomUUID().toString();
        }
        return ref;
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
