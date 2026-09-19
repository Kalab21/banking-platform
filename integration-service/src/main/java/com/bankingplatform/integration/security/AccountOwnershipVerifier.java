package com.bankingplatform.integration.security;

import com.bankingplatform.common.security.AccessDeniedException;
import com.bankingplatform.common.security.AccessGuard;
import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.integration.dto.AccountSummary;
import com.bankingplatform.integration.client.AccountClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Answers "may this caller send money out of this account?".
 *
 * <p>integration-service does not own accounts, so it resolves the owning user
 * from account-service and applies the platform's ownership rule to the
 * answer. This mirrors {@code transaction-service}'s verifier rather than
 * introducing a second model: an external transfer leaves an account exactly
 * as an internal one does, so it is authorised by the same rule, and a reader
 * comparing the two services finds the same shape in both.
 *
 * <p>{@code requireOwnerOrStaff}, not {@code requireSelf}, because that is the
 * established policy for moving money out of an account in this platform —
 * transaction-service applies it to deposit, withdrawal and transfer. Using
 * anything else here would not be least privilege, it would be a third rule
 * for the same kind of action.
 *
 * <p>The check runs <em>before</em> the transfer is persisted. Authorising
 * afterwards would leave a refused request with a durable record and a
 * published Kafka event behind it.
 */
@Component
@RequiredArgsConstructor
public class AccountOwnershipVerifier {

    private final AccountClient accountClient;

    /**
     * Confirms the caller may act on {@code accountId}.
     *
     * <p>A null id is refused here rather than being sent downstream. It
     * cannot identify an account, so it cannot be owned by the caller, and
     * turning it into a request path would ask account-service to interpret
     * caller input this service has already failed to validate.
     */
    public void requireCanAccess(CallerIdentity caller, Long accountId) {
        if (accountId == null) {
            throw new AccessDeniedException("Not permitted to access this resource");
        }
        AccountSummary account = accountClient.getAccountById(accountId);
        AccessGuard.requireOwnerOrStaff(caller, account.getUserId());
    }
}
