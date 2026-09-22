package com.bankingplatform.account.service.impl;

import com.bankingplatform.account.client.UserClient;
import com.bankingplatform.account.dto.BalanceUpdateRequest;
import com.bankingplatform.account.dto.CreateAccountRequest;
import com.bankingplatform.account.kafka.producer.AccountEventProducer;
import com.bankingplatform.account.mapper.AccountMapper;
import com.bankingplatform.account.model.Account;
import com.bankingplatform.account.model.AccountStatus;
import com.bankingplatform.account.model.AccountType;
import com.bankingplatform.account.repository.AccountRepository;
import com.bankingplatform.account.repository.AuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A newly opened account holds nothing.
 *
 * <p>Opening used to copy a caller-supplied {@code initialDeposit} straight
 * into the balance, so an account could be born holding money that no one had
 * paid in: no payer, no transaction row, nothing to reconcile against. The
 * field is gone, and the balance is now written as zero rather than taken from
 * the request, which is what these tests pin down.
 *
 * <p>Money is compared with {@code isEqualByComparingTo} so that a difference
 * in BigDecimal scale never reads as a difference in value.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountServiceImpl — an account opens empty")
class AccountOpeningBalanceTest {

    private static final long USER_ID = 7L;

    @Mock private AccountRepository accountRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private AccountMapper accountMapper;
    @Mock private AccountEventProducer eventProducer;
    @Mock private UserClient userClient;

    @InjectMocks private AccountServiceImpl accountService;

    @Captor private ArgumentCaptor<Account> savedAccount;

    private CreateAccountRequest openRequest(AccountType type) {
        CreateAccountRequest request = new CreateAccountRequest();
        request.setUserId(USER_ID);
        request.setAccountType(type);
        request.setOverdraftLimit(BigDecimal.ZERO);
        return request;
    }

    private Account whenOpened(CreateAccountRequest request) {
        when(accountRepository.existsByAccountNumber(any())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));

        accountService.createAccount(request);

        org.mockito.Mockito.verify(accountRepository).save(savedAccount.capture());
        return savedAccount.getValue();
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    @DisplayName("every account type opens at 0.00")
    void opensAtZero(AccountType type) {
        assertThat(whenOpened(openRequest(type)).getBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the opening request has no field that could carry a balance")
    void requestCannotNameAnOpeningBalance() {
        // The guarantee is structural rather than behavioural: if a funding
        // field is ever added back to the request, provisioning can quietly
        // start minting money again and no behavioural test would notice
        // until the number showed up on somebody's statement.
        String[] fields = Arrays.stream(CreateAccountRequest.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(Field::getName)
                .toArray(String[]::new);

        assertThat(fields)
                .containsExactlyInAnyOrder("userId", "accountType", "currency", "overdraftLimit");
    }

    @Test
    @DisplayName("a deposit after opening still credits the account")
    void depositAfterOpeningStillWorks() {
        // Removing the opening balance must not make an account unfundable:
        // the money-movement path is the one way in, so it has to work.
        Account opened = Account.builder()
                .id(11L)
                .accountNumber("BA250101000001")
                .userId(USER_ID)
                .accountType(AccountType.CHECKING)
                .status(AccountStatus.ACTIVE)
                .balance(BigDecimal.ZERO)
                .currency("USD")
                .overdraftLimit(new BigDecimal("500.00"))
                .overdraftBalance(BigDecimal.ZERO)
                .build();

        BalanceUpdateRequest credit = new BalanceUpdateRequest();
        credit.setOperation("CREDIT");
        credit.setAmount(new BigDecimal("4200.00"));

        when(accountRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(opened));
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));

        accountService.updateBalance(11L, credit);

        org.mockito.Mockito.verify(accountRepository).save(savedAccount.capture());
        assertThat(savedAccount.getValue().getBalance())
                .isEqualByComparingTo(new BigDecimal("4200.00"));
    }
}
