package com.bankingplatform.account.service.impl;

import com.bankingplatform.account.client.UserClient;
import com.bankingplatform.account.dto.BalanceUpdateRequest;
import com.bankingplatform.account.exception.AccountStatusException;
import com.bankingplatform.account.exception.InsufficientFundsException;
import com.bankingplatform.account.exception.ResourceNotFoundException;
import com.bankingplatform.account.kafka.producer.AccountEventProducer;
import com.bankingplatform.account.mapper.AccountMapper;
import com.bankingplatform.account.model.Account;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.model.AccountType;
import com.bankingplatform.account.model.AuditLog;
import com.bankingplatform.account.repository.AccountRepository;
import com.bankingplatform.account.repository.AuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for balance movement on {@link AccountServiceImpl}.
 *
 * <p>These assert on the {@link Account} handed to {@code AccountRepository.save(..)},
 * because that is the state the service actually commits. The mapper is mocked: its
 * output is a view, not the business outcome.
 *
 * <p>Money is compared with {@code isEqualByComparingTo} so that a difference in
 * BigDecimal scale (2.0 vs 2.00) never masquerades as a difference in value.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountServiceImpl — balance movement")
class AccountServiceImplTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long USER_ID = 7L;

    /** Charged by the service whenever a debit pushes an account into overdraft. */
    private static final BigDecimal OVERDRAFT_FEE = new BigDecimal("35.00");

    @Mock private AccountRepository accountRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private AccountMapper accountMapper;
    @Mock private AccountEventProducer eventProducer;
    @Mock private UserClient userClient;

    @InjectMocks private AccountServiceImpl accountService;

    @Captor private ArgumentCaptor<Account> savedAccount;

    // ---------------------------------------------------------------- fixtures

    private static Account account(AccountStatus status,
                                   String balance,
                                   String overdraftLimit,
                                   String overdraftBalance) {
        return Account.builder()
                .id(ACCOUNT_ID)
                .accountNumber("BA250101000001")
                .userId(USER_ID)
                .accountType(AccountType.CHECKING)
                .status(status)
                .balance(new BigDecimal(balance))
                .currency("USD")
                .overdraftLimit(new BigDecimal(overdraftLimit))
                .overdraftBalance(new BigDecimal(overdraftBalance))
                .build();
    }

    private static BalanceUpdateRequest request(String operation, String amount) {
        BalanceUpdateRequest request = new BalanceUpdateRequest();
        request.setOperation(operation);
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    private Account whenBalanceUpdated(Account existing, BalanceUpdateRequest request) {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));

        accountService.updateBalance(ACCOUNT_ID, request);

        verify(accountRepository).save(savedAccount.capture());
        return savedAccount.getValue();
    }

    // ------------------------------------------------------------------ credit

    @Nested
    @DisplayName("credit")
    class Credit {

        @Test
        @DisplayName("increases the balance by the credited amount")
        void creditIncreasesBalance() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.ACTIVE, "100.00", "0.00", "0.00"),
                    request("CREDIT", "250.00"));

            assertThat(result.getBalance()).isEqualByComparingTo("350.00");
            assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("is matched case-insensitively on the operation name")
        void operationIsCaseInsensitive() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.ACTIVE, "10.00", "0.00", "0.00"),
                    request("credit", "5.00"));

            assertThat(result.getBalance()).isEqualByComparingTo("15.00");
        }
    }

    // ------------------------------------------------------------------- debit

    @Nested
    @DisplayName("debit")
    class Debit {

        @Test
        @DisplayName("reduces the balance when funds cover the amount")
        void debitWithinBalance() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.ACTIVE, "500.00", "0.00", "0.00"),
                    request("DEBIT", "200.00"));

            assertThat(result.getBalance()).isEqualByComparingTo("300.00");
            assertThat(result.getOverdraftBalance()).isEqualByComparingTo("0.00");
            assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("draining the balance to exactly zero does not trigger overdraft")
        void debitToExactlyZero() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.ACTIVE, "200.00", "500.00", "0.00"),
                    request("DEBIT", "200.00"));

            assertThat(result.getBalance()).isEqualByComparingTo("0.00");
            assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            verify(eventProducer, never()).publishOverdraftTriggered(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("is rejected when the amount exceeds balance plus overdraft head-room")
        void insufficientFunds() {
            Account existing = account(AccountStatus.ACTIVE, "100.00", "500.00", "0.00");
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));

            // available = 100 + 500 - 0 = 600
            assertThatThrownBy(() -> accountService.updateBalance(ACCOUNT_ID, request("DEBIT", "700.00")))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("Available: 600.00")
                    .hasMessageContaining("Requested: 700.00");

            verify(accountRepository, never()).save(any());
            verify(eventProducer, never()).publishBalanceUpdated(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("head-room already consumed by an existing overdraft is not offered twice")
        void insufficientFundsCountsExistingOverdraft() {
            Account existing = account(AccountStatus.OVERDRAWN, "0.00", "500.00", "400.00");
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));

            // available = 0 + 500 - 400 = 100
            assertThatThrownBy(() -> accountService.updateBalance(ACCOUNT_ID, request("DEBIT", "150.00")))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("Available: 100.00");

            verify(accountRepository, never()).save(any());
        }
    }

    // --------------------------------------------------------------- overdraft

    @Nested
    @DisplayName("overdraft")
    class Overdraft {

        @Test
        @DisplayName("a debit beyond the balance but within the limit moves the deficit to overdraft and charges the fee")
        void overdraftWithinLimit() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.ACTIVE, "100.00", "500.00", "0.00"),
                    request("DEBIT", "400.00"));

            // deficit = 400 - 100 = 300; balance floors at zero, then the fee is applied to it
            assertThat(result.getOverdraftBalance()).isEqualByComparingTo("300.00");
            assertThat(result.getStatus()).isEqualTo(AccountStatus.OVERDRAWN);
            assertThat(result.getBalance()).isEqualByComparingTo(OVERDRAFT_FEE.negate());
        }

        @Test
        @DisplayName("announces the deficit on the event stream")
        void overdraftPublishesEvent() {
            whenBalanceUpdated(
                    account(AccountStatus.ACTIVE, "100.00", "500.00", "0.00"),
                    request("DEBIT", "400.00"));

            verify(eventProducer).publishOverdraftTriggered(
                    eq(ACCOUNT_ID), eq(USER_ID), eq(new BigDecimal("300.00")));
        }

        @Test
        @DisplayName("a debit that consumes the entire limit is still allowed")
        void debitOfExactlyTheAvailableAmount() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.ACTIVE, "100.00", "500.00", "0.00"),
                    request("DEBIT", "600.00"));

            assertThat(result.getOverdraftBalance()).isEqualByComparingTo("500.00");
            assertThat(result.getStatus()).isEqualTo(AccountStatus.OVERDRAWN);
        }

        @Test
        @DisplayName("a credit that clears the overdraft restores the account to ACTIVE")
        void fullOverdraftRepaymentRestoresActive() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.OVERDRAWN, "-35.00", "500.00", "300.00"),
                    request("CREDIT", "300.00"));

            assertThat(result.getOverdraftBalance()).isEqualByComparingTo("0.00");
            assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(result.getBalance()).isEqualByComparingTo("265.00");
        }

        @Test
        @DisplayName("a credit that only part-pays the overdraft leaves the account OVERDRAWN")
        void partialOverdraftRepaymentStaysOverdrawn() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.OVERDRAWN, "-35.00", "500.00", "300.00"),
                    request("CREDIT", "100.00"));

            assertThat(result.getOverdraftBalance()).isEqualByComparingTo("200.00");
            assertThat(result.getStatus()).isEqualTo(AccountStatus.OVERDRAWN);
            assertThat(result.getBalance()).isEqualByComparingTo("65.00");
        }

        @Test
        @DisplayName("a credit larger than the overdraft repays it without over-repaying")
        void overpaymentRepaysOnlyWhatIsOwed() {
            Account result = whenBalanceUpdated(
                    account(AccountStatus.OVERDRAWN, "-35.00", "500.00", "300.00"),
                    request("CREDIT", "1000.00"));

            assertThat(result.getOverdraftBalance()).isEqualByComparingTo("0.00");
            assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(result.getBalance()).isEqualByComparingTo("965.00");
        }
    }

    // ------------------------------------------------------------ status gates

    @Nested
    @DisplayName("account status")
    class Status {

        @ParameterizedTest(name = "a {0} account rejects a debit")
        @EnumSource(value = AccountStatus.class, names = {"FROZEN", "CLOSED"})
        void blockedStatusRejectsDebit(AccountStatus status) {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account(status, "500.00", "0.00", "0.00")));

            assertThatThrownBy(() -> accountService.updateBalance(ACCOUNT_ID, request("DEBIT", "10.00")))
                    .isInstanceOf(AccountStatusException.class)
                    .hasMessageContaining(status.name());

            verify(accountRepository, never()).save(any());
        }

        @ParameterizedTest(name = "a {0} account rejects a credit")
        @EnumSource(value = AccountStatus.class, names = {"FROZEN", "CLOSED"})
        void blockedStatusRejectsCredit(AccountStatus status) {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account(status, "500.00", "0.00", "0.00")));

            assertThatThrownBy(() -> accountService.updateBalance(ACCOUNT_ID, request("CREDIT", "10.00")))
                    .isInstanceOf(AccountStatusException.class);

            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("a closed account cannot be reopened")
        void closedAccountCannotBeReopened() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account(AccountStatus.CLOSED, "0.00", "0.00", "0.00")));

            assertThatThrownBy(() -> accountService.updateStatus(ACCOUNT_ID, AccountStatus.ACTIVE))
                    .isInstanceOf(AccountStatusException.class)
                    .hasMessageContaining("Cannot reopen a closed account");

            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("an active account can be frozen")
        void activeAccountCanBeFrozen() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "0.00", "0.00", "0.00")));
            when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));

            accountService.updateStatus(ACCOUNT_ID, AccountStatus.FROZEN);

            verify(accountRepository).save(savedAccount.capture());
            assertThat(savedAccount.getValue().getStatus()).isEqualTo(AccountStatus.FROZEN);
        }

        @Test
        @DisplayName("an unknown account id is reported as not found")
        void unknownAccountIsNotFound() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.updateBalance(ACCOUNT_ID, request("CREDIT", "10.00")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(String.valueOf(ACCOUNT_ID));
        }
    }

    // ------------------------------------------------------- audit and events

    @Nested
    @DisplayName("audit trail and events")
    class AuditAndEvents {

        @Test
        @DisplayName("every committed balance change writes an audit row and publishes an event")
        void balanceChangeIsAuditedAndPublished() {
            whenBalanceUpdated(
                    account(AccountStatus.ACTIVE, "100.00", "0.00", "0.00"),
                    request("CREDIT", "50.00"));

            ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(audit.capture());
            assertThat(audit.getValue().getEntityType()).isEqualTo("ACCOUNT");
            assertThat(audit.getValue().getEntityId()).isEqualTo(ACCOUNT_ID);
            assertThat(audit.getValue().getAction()).isEqualTo("CREDIT");

            verify(eventProducer).publishBalanceUpdated(
                    eq(ACCOUNT_ID), eq(USER_ID), eq(new BigDecimal("150.00")), eq("CREDIT"));
        }

        @Test
        @DisplayName("a rejected debit writes no audit row and publishes nothing")
        void rejectedDebitLeavesNoTrace() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account(AccountStatus.ACTIVE, "10.00", "0.00", "0.00")));

            assertThatThrownBy(() -> accountService.updateBalance(ACCOUNT_ID, request("DEBIT", "999.00")))
                    .isInstanceOf(InsufficientFundsException.class);

            verify(auditLogRepository, never()).save(any());
            verify(eventProducer, never()).publishBalanceUpdated(anyLong(), anyLong(), any(), any());
        }
    }
}
