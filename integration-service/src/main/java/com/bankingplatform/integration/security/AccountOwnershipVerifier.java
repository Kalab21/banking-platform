package com.bankingplatform.integration.security;

import com.bankingplatform.common.security.AccessDeniedException;
import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.common.security.MissingCallerIdentityException;
import com.bankingplatform.integration.client.AccountClient;
import com.bankingplatform.integration.dto.AccountSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Answers who may send money out of an account, and who may read that it was sent.
 *
 * <p>integration-service does not own accounts, so it resolves the owning user
 * from account-service — the service that actually holds the account — and
 * applies the platform's rules to the answer. The request is never treated as
 * proof of its own ownership.
 *
 * <p>Reading and sending are deliberately <em>different</em> rules, and the
 * asymmetry is the point:
 *
 * <ul>
 *   <li><b>Reading</b> is {@code requireOwnerOrStaff}. Staff reading a
 *       customer's records is established policy — it is what the support
 *       workflows need, it is in the authorization table in
 *       {@code docs/SECURITY.md}, and {@code TransactionAuthorizationTest}
 *       asserts it for transaction history.</li>
 *   <li><b>Sending</b> is owner-only. An external transfer is the one action
 *       in this platform that moves money <em>out of the bank</em>, along a
 *       rail with no in-product reversal. Nothing in this repository
 *       establishes that an employee may initiate one on a customer's behalf:
 *       the authorization table grants staff nothing on the money-movement
 *       row, and no test asserts it. Where policy is silent about an
 *       irreversible outward payment, the safe reading is the narrow one, so
 *       staff get no bypass here and the decision is written down rather than
 *       inherited by accident from a shared helper.</li>
 * </ul>
 *
 * <p>This is a deliberate narrowing, not an oversight: {@code
 * transaction-service} authorises internal money movement with
 * {@code requireOwnerOrStaff}, and a reader comparing the two will find this
 * comment explaining why the outward rail is stricter. Widening it is a
 * product decision, and would want a staff-initiated-transfer audit trail
 * before it were sensible.
 */
@Component
@RequiredArgsConstructor
public class AccountOwnershipVerifier {

    private final AccountClient accountClient;

    /**
     * The caller may initiate an external transfer out of {@code accountId}.
     *
     * <p>Called <em>before</em> the transfer is persisted: authorising
     * afterwards would leave a refused request with a durable record and a
     * published Kafka event behind it.
     */
    public void requireCanSendFrom(CallerIdentity caller, Long accountId) {
        AccessGuard.requireSelf(caller, ownerOf(caller, accountId));
    }

    /** The caller may read a transfer that was sent out of {@code accountId}. */
    public void requireCanRead(CallerIdentity caller, Long accountId) {
        AccessGuard.requireOwnerOrStaff(caller, ownerOf(caller, accountId));
    }

    /**
     * Resolves the owning user, refusing an id that cannot identify an account.
     *
     * <p>A null id is refused here rather than being sent downstream. It cannot
     * identify an account, so it cannot be owned by the caller, and turning it
     * into a request path would ask account-service to interpret caller input
     * this service has already failed to validate.
     *
     * <p>The caller is checked for presence first, so a request that carries no
     * gateway identity is answered 401 without a downstream lookup, rather than
     * being told 403 about an account it never proved anything about.
     */
    private Long ownerOf(CallerIdentity caller, Long accountId) {
        if (caller == null || caller.userId() == null || caller.role() == null) {
            throw new MissingCallerIdentityException("No caller identity on this request");
        }
        if (accountId == null) {
            throw new AccessDeniedException("Not permitted to access this resource");
        }
        AccountSummary account = accountClient.getAccountById(accountId);
        return account.getUserId();
    }
}
