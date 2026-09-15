package com.bankingplatform.payment.dto;

import lombok.Data;

@Data
public class TransferResponse {
    private TransactionResponse debit;
    private TransactionResponse credit;
}
