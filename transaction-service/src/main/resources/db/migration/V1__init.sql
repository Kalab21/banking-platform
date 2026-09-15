CREATE TABLE transactions (
    id                      BIGSERIAL PRIMARY KEY,
    transaction_ref         VARCHAR(36) NOT NULL UNIQUE,
    account_id              BIGINT NOT NULL,
    type                    VARCHAR(30) NOT NULL,
    amount                  DECIMAL(19,2) NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance_after           DECIMAL(19,2) NOT NULL,
    description             VARCHAR(255),
    related_transaction_ref VARCHAR(36),
    status                  VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_account_id ON transactions(account_id);
CREATE INDEX idx_transactions_ref ON transactions(transaction_ref);
CREATE INDEX idx_transactions_created_at ON transactions(created_at DESC);
CREATE INDEX idx_transactions_account_created ON transactions(account_id, created_at DESC);

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    BIGINT NOT NULL,
    action       VARCHAR(50) NOT NULL,
    performed_by BIGINT,
    details      VARCHAR(1000),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
