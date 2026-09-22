package com.bankingplatform.creditcard.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * As much of an account as this service needs to decide whether the customer
 * moving money is allowed to move <em>this</em> money.
 *
 * <p>Deliberately not the whole account. A service that settles a debt has no
 * business reading someone's balance, and a narrow view cannot leak what it
 * never asked for.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountOwnerView {
    private Long id;
    private Long userId;
    private String status;
    private String currency;
}
