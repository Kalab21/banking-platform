CREATE TABLE platform_stats (
    id                       SERIAL PRIMARY KEY,
    total_accounts           BIGINT        NOT NULL DEFAULT 0,
    total_transactions       BIGINT        NOT NULL DEFAULT 0,
    transaction_volume       DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    total_payments           BIGINT        NOT NULL DEFAULT 0,
    payment_volume           DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    failed_payments          BIGINT        NOT NULL DEFAULT 0,
    total_applications       BIGINT        NOT NULL DEFAULT 0,
    approved_applications    BIGINT        NOT NULL DEFAULT 0,
    rejected_applications    BIGINT        NOT NULL DEFAULT 0,
    total_credit_cards       BIGINT        NOT NULL DEFAULT 0,
    cc_transactions          BIGINT        NOT NULL DEFAULT 0,
    cc_spend                 DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    total_loans              BIGINT        NOT NULL DEFAULT 0,
    total_loan_disbursed     DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    total_repayments         BIGINT        NOT NULL DEFAULT 0,
    total_repayment_volume   DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    loans_paid_off           BIGINT        NOT NULL DEFAULT 0,
    last_updated             TIMESTAMP     NOT NULL DEFAULT NOW()
);

INSERT INTO platform_stats DEFAULT VALUES;

CREATE TABLE user_stats (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT        NOT NULL UNIQUE,
    total_transactions    BIGINT        NOT NULL DEFAULT 0,
    total_amount_in       DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    total_amount_out      DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    total_payments        BIGINT        NOT NULL DEFAULT 0,
    total_payment_volume  DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    total_accounts        INT           NOT NULL DEFAULT 0,
    active_loans          INT           NOT NULL DEFAULT 0,
    total_loan_amount     DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    cc_transactions       BIGINT        NOT NULL DEFAULT 0,
    cc_spend              DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    last_updated          TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_stats_user_id ON user_stats(user_id);

CREATE TABLE daily_snapshots (
    id                    BIGSERIAL PRIMARY KEY,
    snapshot_date         DATE          NOT NULL UNIQUE,
    new_accounts          INT           NOT NULL DEFAULT 0,
    new_transactions      INT           NOT NULL DEFAULT 0,
    transaction_volume    DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    new_payments          INT           NOT NULL DEFAULT 0,
    payment_volume        DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    new_applications      INT           NOT NULL DEFAULT 0,
    approved_applications INT           NOT NULL DEFAULT 0,
    new_loans             INT           NOT NULL DEFAULT 0,
    loan_volume           DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    cc_transactions       INT           NOT NULL DEFAULT 0,
    cc_spend              DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    created_at            TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_snapshots_date ON daily_snapshots(snapshot_date);
