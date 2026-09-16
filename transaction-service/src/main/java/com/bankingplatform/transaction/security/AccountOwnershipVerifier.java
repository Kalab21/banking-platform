package com.bankingplatform.transaction.security;

import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.transaction.client.AccountClient;
import com.bankingplatform.transaction.dto.AccountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Answers "may this caller move money on this account?".
 *
 * <p>transaction-service does not own accounts, so it resolves the owning user
 * from account-service and applies the platform's ownership rule to the answer.
 *
 * <p>The check runs <em>before</em> any balance call. Authorising after a debit
 * would leave money already moved by the time the request is refused, which is
 * the difference between a rejected transfer and a transfer that has to be
 * unwound by hand.
 */
@Component
@RequiredArgsConstructor
public class AccountOwnershipVerifier {

    private final AccountClient accountClient;

    /**
     * Confirms the caller may act on {@code accountId}.
     *
     * <p>Two checks, deliberately: account-service authorises the lookup itself
     * because the caller's identity is forwarded on the hop, and the owning
     * user is then checked here as well. Either alone would be sufficient
     * today; together, adding a new caller of this method cannot quietly skip
     * the rule.
     */
    public void requireCanAccess(CallerIdentity caller, Long accountId) {
        AccountResponse account = accountClient.getAccountById(accountId);
        AccessGuard.requireOwnerOrStaff(caller, account.getUserId());
    }
}
