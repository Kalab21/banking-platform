package com.bankingplatform.statistics.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class DailySnapshotResponse implements Serializable {
    private LocalDate snapshotDate;
    private Integer newAccounts;
    private Integer newTransactions;
    private BigDecimal transactionVolume;
    private Integer newPayments;
    private BigDecimal paymentVolume;
    private Integer newApplications;
    private Integer approvedApplications;
    private Integer newLoans;
    private BigDecimal loanVolume;
    private Integer ccTransactions;
    private BigDecimal ccSpend;
}
