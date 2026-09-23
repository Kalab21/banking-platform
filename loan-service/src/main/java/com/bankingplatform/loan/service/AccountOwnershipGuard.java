package com.bankingplatform.loan.service;

import com.bankingplatform.loan.client.AccountClient;
import com.bankingplatform.loan.client.AccountOwnerView;
import lombok.RequiredArgsConstructor;
import com.bankingplatform.common.security.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Both sides of a money movement, not just one.
 *
 * <p>Owning a loan is authority over the loan. It is not authority over the
 * account the repayment is taken from. Every write here used to check the first
 * and not the second, so a customer could settle their own debt out of an
 * account they had merely guessed the id of: they owned the product, and
 * nothing asked whether they owned the money.
 *
 * <p>The owner comes from the stored account, never from the request. An id in
 * a request body says which account is wanted; it says nothing about who is
 * entitled to it.
 */
@Component
@RequiredArgsConstructor
public class AccountOwnershipGuard {

    private final AccountClient accountClient;

    /**
     * Refuses unless this account belongs to this customer.
     *
     * <p>Staff are not exempt. A member of staff moving a customer's money
     * between that customer's own products is the same operation; moving it out
     * of somebody else's account is not something this path should allow
     * anyone to do.
     */
    public void requireOwnedBy(Long accountId, Long ownerUserId, String what) {
        if (accountId == null) {
            return;
        }
        AccountOwnerView account = accountClient.getAccount(accountId);
        if (account == null || account.getUserId() == null
                || !account.getUserId().equals(ownerUserId)) {
            // Deliberately says nothing about whether the account exists.
            throw new AccessDeniedException(
                    "The " + what + " account does not belong to this customer");
        }
    }
}
