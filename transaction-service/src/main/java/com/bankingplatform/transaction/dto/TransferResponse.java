package com.bankingplatform.transaction.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransferResponse {
    private TransactionResponse debit;
    private TransactionResponse credit;
}
