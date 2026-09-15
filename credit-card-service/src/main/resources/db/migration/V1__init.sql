CREATE TABLE credit_cards (
    id                    BIGSERIAL PRIMARY KEY,
    card_number           VARCHAR(16) NOT NULL UNIQUE,
    user_id               BIGINT NOT NULL,
    application_id        BIGINT,
    card_type             VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    credit_limit          DECIMAL(19,2) NOT NULL,
    available_credit      DECIMAL(19,2) NOT NULL,
    current_balance       DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    statement_balance     DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    minimum_payment_due   DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    payment_due_date      DATE,
    apr                   DECIMAL(5,2) NOT NULL,
    daily_rate            DECIMAL(12,10) NOT NULL,
    billing_cycle_day     INT NOT NULL DEFAULT 1,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    currency              VARCHAR(3) NOT NULL DEFAULT 'USD',
    rewards_points        INT NOT NULL DEFAULT 0,
    linked_account_id     BIGINT,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_credit_cards_user_id ON credit_cards(user_id);

CREATE TABLE credit_card_transactions (
    id              BIGSERIAL PRIMARY KEY,
    credit_card_id  BIGINT NOT NULL REFERENCES credit_cards(id),
    transaction_ref VARCHAR(36) NOT NULL UNIQUE,
    type            VARCHAR(30) NOT NULL,
    amount          DECIMAL(19,2) NOT NULL,
    description     VARCHAR(255),
    merchant_name   VARCHAR(100),
    merchant_category VARCHAR(50),
    status          VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cc_transactions_card_id ON credit_card_transactions(credit_card_id);
CREATE INDEX idx_cc_transactions_created ON credit_card_transactions(credit_card_id, created_at DESC);

CREATE TABLE credit_card_statements (
    id               BIGSERIAL PRIMARY KEY,
    credit_card_id   BIGINT NOT NULL REFERENCES credit_cards(id),
    statement_date   DATE NOT NULL,
    opening_balance  DECIMAL(19,2) NOT NULL,
    closing_balance  DECIMAL(19,2) NOT NULL,
    total_purchases  DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    total_payments   DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    interest_charged DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    fees_charged     DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    rewards_earned   INT NOT NULL DEFAULT 0,
    minimum_payment  DECIMAL(19,2) NOT NULL,
    payment_due_date DATE NOT NULL,
    paid_in_full     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (credit_card_id, statement_date)
);

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    BIGINT NOT NULL,
    action       VARCHAR(50) NOT NULL,
    performed_by BIGINT,
    details      VARCHAR(1000),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
