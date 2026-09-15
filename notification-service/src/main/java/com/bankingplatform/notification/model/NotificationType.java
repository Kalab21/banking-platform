package com.bankingplatform.notification.model;

public enum NotificationType {
    // Transaction alerts
    LARGE_TRANSACTION_ALERT,
    OVERDRAFT_ALERT,

    // Payment
    PAYMENT_RECEIPT,
    PAYMENT_FAILED,

    // Application
    APPLICATION_APPROVED,
    APPLICATION_REJECTED,

    // Credit card
    CREDIT_CARD_PAYMENT_DUE,
    CREDIT_CARD_STATEMENT_AVAILABLE,
    CREDIT_CARD_ISSUED,

    // Loan
    LOAN_PAYMENT_DUE,
    LOAN_LATE_NOTICE,
    LOAN_PAID_OFF,
    LOAN_DISBURSED,

    // Account
    ACCOUNT_CREATED,

    // KYC / 2FA
    KYC_APPROVED,
    KYC_REJECTED,
    TWO_FA_ENABLED,
    TWO_FA_DISABLED,

    // General
    GENERAL
}
