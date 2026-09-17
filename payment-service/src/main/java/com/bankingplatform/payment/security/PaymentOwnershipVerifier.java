package com.bankingplatform.payment.security;

import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.payment.client.AccountClient;
import com.bankingplatform.payment.dto.AccountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Answers "may this caller see or act on this payment?".
 *
 * <p>payment-service does not own accounts, so a payment's owner is the owner
 * of the account it is paid from. That is resolved from account-service and the
 * platform's ownership rule is applied to the answer, the same way
 * transaction-service resolves it.
 *
 * <p>The distinction that matters: an account id in a path, or a {@code userId}
 * in a body or query string, is caller input. It says which resource is wanted,
 * never who is entitled to it. Everything here authorises against an owner
 * resolved from stored state instead.
 */
@Component
@RequiredArgsConstructor
public class PaymentOwnershipVerifier {

    private final AccountClient accountClient;

    /** Confirms the caller owns {@code accountId}, or is staff. */
    public void requireCanAccessAccount(CallerIdentity caller, Long accountId) {
        AccountResponse account = accountClient.getAccountById(accountId);
        AccessGuard.requireOwnerOrStaff(caller, account.getUserId());
    }
}
