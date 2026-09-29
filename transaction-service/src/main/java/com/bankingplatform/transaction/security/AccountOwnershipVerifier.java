package com.bankingplatform.transaction.security;

import com.bankingplatform.transaction.exception.TransactionException;
import feign.FeignException;

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
    public AccountResponse requireCanAccess(CallerIdentity caller, Long accountId) {
        AccountResponse account = accountClient.getAccountById(accountId);
        AccessGuard.requireOwnerOrStaff(caller, account.getUserId());
        return account;
    }

    /**
     * Confirms the caller may take money out of {@code accountId}: its owner,
     * and nobody else.
     *
     * <p>Staff may read an account and may deposit to it, but a withdrawal or
     * a transfer out is the holder's instruction. Owner-or-staff here let any
     * employee move a customer's money to an account of their choosing.
     */
    public AccountResponse requireCanDebit(CallerIdentity caller, Long accountId) {
        AccountResponse account = accountClient.getAccountById(accountId);
        AccessGuard.requireSelf(caller, account.getUserId());
        return account;
    }

    /**
     * The currency a request on {@code account} is in: the account's own.
     *
     * <p>Every account holds one currency and nothing on this path converts,
     * so a request naming another was applied as if it were the account's
     * currency and recorded under the name it gave: 100 "EUR" credited 100 USD
     * and was stored as a EUR deposit. It is refused instead, before any
     * balance call, and a request naming none is recorded in the account's.
     */
    public static String requireAccountCurrency(AccountResponse account, String requested) {
        String held = account.getCurrency();
        if (requested == null || requested.isBlank()) {
            return held;
        }
        if (held != null && !held.equalsIgnoreCase(requested.trim())) {
            throw new TransactionException("This account holds " + held
                    + "; the amount has to be in " + held);
        }
        return held != null ? held : requested.trim();
    }

    /**
     * Confirms a transfer's destination can take the money, before anything
     * is debited.
     *
     * <p>The debit runs first, so a destination that turned out not to exist,
     * or to be frozen or closed, used to fail the credit after the source had
     * already paid: a half-applied transfer left for a person to repair. A
     * mistyped account number cost the customer their money. Checked here,
     * with the source, it is a refusal instead.
     */
    public void requireCanReceive(AccountResponse source, Long destinationId) {
        AccountResponse destination;
        try {
            destination = accountClient.getAccountInternal(destinationId);
        } catch (FeignException.NotFound missing) {
            throw new TransactionException("That account cannot receive a transfer");
        }
        if (destination == null || "FROZEN".equals(destination.getStatus()) || "CLOSED".equals(destination.getStatus())) {
            throw new TransactionException("That account cannot receive a transfer");
        }
        // The same number moves on both sides, so the two accounts have to
        // count it in the same currency; there is no conversion on this path.
        if (source.getCurrency() != null && destination.getCurrency() != null
                && !source.getCurrency().equals(destination.getCurrency())) {
            throw new TransactionException("Transfers between currencies are not supported");
        }
    }
}
