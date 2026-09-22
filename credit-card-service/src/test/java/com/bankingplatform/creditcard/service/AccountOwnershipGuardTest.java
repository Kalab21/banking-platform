package com.bankingplatform.creditcard.service;

import com.bankingplatform.common.security.AccessDeniedException;
import com.bankingplatform.creditcard.client.AccountClient;
import com.bankingplatform.creditcard.client.AccountOwnerView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Owning the card is not authority over the account that pays for it.
 *
 * <p>Every write in this service checked the first and not the second, so a
 * customer could pay down their own card out of an account they had guessed the id
 * of. The victim's balance went down; the attacker's debt went down with it.
 */
@DisplayName("Account ownership")
class AccountOwnershipGuardTest {

    private static final long CARDHOLDER = 10L;
    private static final long VICTIM = 20L;
    private static final long ACCOUNT = 500L;

    private AccountClient accountClient;
    private AccountOwnershipGuard guard;

    @BeforeEach
    void setUp() {
        accountClient = Mockito.mock(AccountClient.class);
        guard = new AccountOwnershipGuard(accountClient);
    }

    private void accountOwnedBy(long userId) {
        AccountOwnerView view = new AccountOwnerView();
        view.setId(ACCOUNT);
        view.setUserId(userId);
        when(accountClient.getAccount(ACCOUNT)).thenReturn(view);
    }

    @Test
    @DisplayName("a customer may use their own account")
    void ownAccountAllowed() {
        accountOwnedBy(CARDHOLDER);

        assertThatCode(() -> guard.requireOwnedBy(ACCOUNT, CARDHOLDER, "source"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a customer may not pay their card from someone else's account")
    void victimAccountRefused() {
        accountOwnedBy(VICTIM);

        assertThatThrownBy(() -> guard.requireOwnedBy(ACCOUNT, CARDHOLDER, "source"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the refusal does not say whether the account exists")
    void refusalLeaksNothing() {
        accountOwnedBy(VICTIM);

        assertThatThrownBy(() -> guard.requireOwnedBy(ACCOUNT, CARDHOLDER, "source"))
                .hasMessageNotContaining(String.valueOf(ACCOUNT))
                .hasMessageNotContaining(String.valueOf(VICTIM));
    }

    @Test
    @DisplayName("an account that cannot be read is refused rather than assumed")
    void unreadableAccountRefused() {
        when(accountClient.getAccount(ACCOUNT)).thenReturn(null);

        assertThatThrownBy(() -> guard.requireOwnedBy(ACCOUNT, CARDHOLDER, "source"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an account with no owner recorded is refused")
    void ownerlessAccountRefused() {
        AccountOwnerView view = new AccountOwnerView();
        view.setId(ACCOUNT);
        when(accountClient.getAccount(ACCOUNT)).thenReturn(view);

        assertThatThrownBy(() -> guard.requireOwnedBy(ACCOUNT, CARDHOLDER, "source"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("no account named means nothing to check")
    void nullAccountIsNotChecked() {
        // A repayment that moves no money from an account is a legitimate
        // shape; refusing it here would break it for no security gain.
        assertThatCode(() -> guard.requireOwnedBy(null, CARDHOLDER, "source"))
                .doesNotThrowAnyException();
        Mockito.verifyNoInteractions(accountClient);
    }
}
