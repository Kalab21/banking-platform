package com.bankingplatform.transaction.service.impl;

import com.bankingplatform.transaction.client.AccountClient;
import com.bankingplatform.transaction.dto.*;
import com.bankingplatform.transaction.exception.ResourceNotFoundException;
import com.bankingplatform.transaction.exception.TransactionException;
import com.bankingplatform.transaction.exception.TransferPartiallyAppliedException;
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
        // Minted before the call, not after, so it can name the movement. A
        // reference generated afterwards is a different value on every
        // attempt, which is exactly what an idempotency key must not be.
        String ref = generateRef();

        AccountResponse account = accountClient.updateBalance(
                request.getAccountId(),
                balanceKey(ref),
                BalanceUpdateRequest.builder()
                        .amount(request.getAmount())
                        .operation("CREDIT")
                        .build()
        );
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
                account.getUserId(), saved.getType().name(), saved.getAmount(),
                saved.getBalanceAfter(), ref);

        return transactionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public TransactionResponse withdraw(WithdrawRequest request) {
        String ref = generateRef();

        AccountResponse account = accountClient.updateBalance(
                request.getAccountId(),
                balanceKey(ref),
                BalanceUpdateRequest.builder()
                        .amount(request.getAmount())
                        .operation("DEBIT")
                        .build()
        );
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
                account.getUserId(), saved.getType().name(), saved.getAmount(),
                saved.getBalanceAfter(), ref);

        return transactionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new TransactionException("Cannot transfer to the same account");
        }

        // Both references are minted up front, because each names its own
        // leg to account-service. Generated after the calls, as they were,
        // they would be new values on every attempt -- and the two legs would
        // have nothing stable to be keyed by.
        String debitRef = generateRef();
        String creditRef = generateRef();

        // Debit source
        AccountResponse fromAccount = accountClient.updateBalance(
                request.getFromAccountId(),
                balanceKey(debitRef),
                BalanceUpdateRequest.builder()
                        .amount(request.getAmount())
                        .operation("DEBIT")
                        .build()
        );

        // Credit destination.
        //
        // The debit above has already been applied by another service, in
        // another database. @Transactional on this method covers the rows
        // written below and nothing else: rolling back here removes the local
        // transaction records and leaves the source account short. Rather than
        // report the credit's error as though the transfer had not started,
        // say plainly that the two legs disagree — and let the idempotency
        // record settle as UNKNOWN, so a retry cannot debit the source twice.
        AccountResponse toAccount;
        try {
            toAccount = accountClient.updateBalance(
                    request.getToAccountId(),
                    balanceKey(creditRef),
                    BalanceUpdateRequest.builder()
                            .amount(request.getAmount())
                            .operation("CREDIT")
                            .build()
            );
        } catch (RuntimeException creditFailure) {
            log.error("Transfer from account {} to account {} debited the source but the credit failed",
                    request.getFromAccountId(), request.getToAccountId(), creditFailure);
            throw new TransferPartiallyAppliedException(
                    "The debit was applied but the credit did not complete, and it has not been "
                            + "reversed. This transfer needs reconciliation before it is reissued.",
                    creditFailure);
        }

        String currency = request.getCurrency() != null ? request.getCurrency() : fromAccount.getCurrency();
        String desc = request.getDescription() != null ? request.getDescription() : "Transfer";

        Transaction debit = transactionRepository.save(Transaction.builder()
                .transactionRef(debitRef)
                .accountId(request.getFromAccountId())
                .type(TransactionType.TRANSFER_OUT)
                .amount(request.getAmount())
                .currency(currency)
                .balanceAfter(fromAccount.getBalance())
                .description(desc + " → " + masked(toAccount))
                .relatedTransactionRef(creditRef)
                .build());

        Transaction credit = transactionRepository.save(Transaction.builder()
                .transactionRef(creditRef)
                .accountId(request.getToAccountId())
                .type(TransactionType.TRANSFER_IN)
                .amount(request.getAmount())
                .currency(currency)
                .balanceAfter(toAccount.getBalance())
                .description(desc + " ← " + masked(fromAccount))
                .relatedTransactionRef(debitRef)
                .build());

        audit("TRANSACTION", debit.getId(), "TRANSFER_OUT", null,
                "From " + request.getFromAccountId() + " to " + request.getToAccountId() + " amount=" + request.getAmount());

        eventProducer.publishTransferCompleted(debitRef, creditRef,
                request.getFromAccountId(), fromAccount.getUserId(),
                request.getToAccountId(), request.getAmount());

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


    /**
     * How the other side of a transfer is named in text a customer reads.
     *
     * It used to be the internal account id, which is a primary key: it means
     * nothing to the person reading their history, and it is not ours to put in
     * front of them. The last four digits are what the console shows everywhere
     * else, so the history now agrees with the rest of the product.
     */
    private static String masked(AccountResponse account) {
        String number = account != null ? account.getAccountNumber() : null;
        return number != null && number.length() >= 4
                ? "••••" + number.substring(number.length() - 4)
                : "another account";
    }

    /**
     * The key one balance movement is applied under.
     *
     * <p>Derived from the transaction reference, which is minted once per
     * movement and does not change when the call is retried -- so a retry is
     * recognised as the same movement, and a genuinely new movement, even for
     * the same amount between the same accounts, is not.
     *
     * <p>A transfer sends two, one per leg. They are different references, so
     * the debit and the credit are separately idempotent: a retry that
     * re-sends both replays the debit and applies the credit, which is
     * precisely the recovery wanted after a credit that failed.
     */
    private String balanceKey(String transactionRef) {
        return "txn-" + transactionRef;
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
