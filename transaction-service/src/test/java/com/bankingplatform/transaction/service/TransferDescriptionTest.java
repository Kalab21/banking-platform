package com.bankingplatform.transaction.service;

import com.bankingplatform.transaction.client.AccountClient;
import com.bankingplatform.transaction.dto.AccountResponse;
import com.bankingplatform.transaction.dto.BalanceUpdateRequest;
import com.bankingplatform.transaction.dto.TransferRequest;
import com.bankingplatform.transaction.kafka.producer.TransactionEventProducer;
import com.bankingplatform.transaction.mapper.TransactionMapper;
import com.bankingplatform.transaction.model.AuditLog;
import com.bankingplatform.transaction.model.Transaction;
import com.bankingplatform.transaction.repository.AuditLogRepository;
import com.bankingplatform.transaction.repository.TransactionRepository;
import com.bankingplatform.transaction.service.impl.TransactionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a transfer says it did, in the words the customer reads.
 *
 * Each leg of a transfer carries a description, and that description is shown
 * in transaction history. It used to name the other side by its internal
 * account id — a primary key, meaningless to the customer and not theirs to
 * see. These tests hold the description to the same masking the rest of the
 * product uses.
 */
@ExtendWith(MockitoExtension.class)
class TransferDescriptionTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private TransactionMapper transactionMapper;
    @Mock private TransactionEventProducer eventProducer;
    @Mock private AccountClient accountClient;

    private static final long FROM_ID = 71L;
    private static final long TO_ID = 69L;

    private TransactionServiceImpl service() {
        // The attempt recorder is mocked: what this test is about is the
        // description a transfer writes, not the durable record of it.
        return new TransactionServiceImpl(
                transactionRepository, auditLogRepository, transactionMapper, eventProducer,
                Mockito.mock(TransferAttemptRecorder.class), accountClient);
    }

    private static AccountResponse account(long id, String number) {
        AccountResponse account = new AccountResponse();
        account.setId(id);
        account.setAccountNumber(number);
        account.setBalance(new BigDecimal("100.00"));
        account.setCurrency("USD");
        return account;
    }

    private static TransferRequest request(String description) {
        TransferRequest request = new TransferRequest();
        request.setFromAccountId(FROM_ID);
        request.setToAccountId(TO_ID);
        request.setAmount(new BigDecimal("25.00"));
        request.setDescription(description);
        return request;
    }

    private List<Transaction> legsFor(String description, String fromNumber, String toNumber) {
        when(accountClient.updateBalance(anyLong(), anyString(), any(BalanceUpdateRequest.class)))
                .thenAnswer(invocation -> {
                    long id = invocation.getArgument(0);
                    return id == FROM_ID ? account(FROM_ID, fromNumber) : account(TO_ID, toNumber);
                });
        lenient().when(transactionRepository.existsByTransactionRef(anyString())).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(auditLogRepository.save(any(AuditLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().transfer(request(description));

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, Mockito.times(2)).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    @DisplayName("each leg names the other account by its last four digits, not its id")
    void namesTheOtherAccountMasked() {
        List<Transaction> legs = legsFor("Monthly saving", "BA2026000000004542", "BA2026000000009716");

        assertThat(legs.get(0).getDescription()).isEqualTo("Monthly saving → ••••9716");
        assertThat(legs.get(1).getDescription()).isEqualTo("Monthly saving ← ••••4542");
    }

    @Test
    @DisplayName("no internal account id reaches the customer's history")
    void neverPrintsAnInternalId() {
        List<Transaction> legs = legsFor(null, "BA2026000000004542", "BA2026000000009716");

        assertThat(legs)
                .extracting(Transaction::getDescription)
                .allSatisfy(description -> assertThat(description)
                        .doesNotContain("account " + FROM_ID)
                        .doesNotContain("account " + TO_ID));
    }

    @Test
    @DisplayName("no full account number reaches the description either")
    void neverPrintsAFullNumber() {
        String from = "BA2026000000004542";
        String to = "BA2026000000009716";

        assertThat(legsFor("Rent", from, to))
                .extracting(Transaction::getDescription)
                .allSatisfy(description -> assertThat(description)
                        .doesNotContain(from)
                        .doesNotContain(to));
    }

    @Test
    @DisplayName("an account with no number is described rather than left blank")
    void survivesAnAccountWithoutANumber() {
        List<Transaction> legs = legsFor("Rent", "BA2026000000004542", null);

        assertThat(legs.get(0).getDescription()).isEqualTo("Rent → another account");
    }
}
