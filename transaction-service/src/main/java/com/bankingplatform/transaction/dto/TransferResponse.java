package com.bankingplatform.transaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The two legs of a transfer.
 *
 * <p>Carries a no-argument constructor because a replayed idempotent transfer
 * is read back out of the stored response body, and Jackson needs one to do
 * that.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponse {
    private TransactionResponse debit;
    private TransactionResponse credit;
}
