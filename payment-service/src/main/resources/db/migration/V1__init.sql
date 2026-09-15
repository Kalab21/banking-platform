CREATE TABLE beneficiaries (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    name             VARCHAR(100) NOT NULL,
    nickname         VARCHAR(50),
    account_number   VARCHAR(34),
    bank_name        VARCHAR(100),
    routing_number   VARCHAR(9),
    swift_code       VARCHAR(11),
    iban             VARCHAR(34),
    beneficiary_type VARCHAR(20) NOT NULL,
    currency         VARCHAR(3) NOT NULL DEFAULT 'USD',
    is_verified      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_beneficiaries_user_id ON beneficiaries(user_id);

CREATE TABLE payments (
    id                   BIGSERIAL PRIMARY KEY,
    payment_ref          VARCHAR(36) NOT NULL UNIQUE,
    payer_account_id     BIGINT NOT NULL,
    beneficiary_id       BIGINT REFERENCES beneficiaries(id),
    payee_account_id     BIGINT,
    payee_external_ref   VARCHAR(100),
    payment_type         VARCHAR(20) NOT NULL,
    amount               DECIMAL(19,2) NOT NULL,
    currency             VARCHAR(3) NOT NULL DEFAULT 'USD',
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    description          VARCHAR(255),
    is_recurring         BOOLEAN NOT NULL DEFAULT FALSE,
    recurrence_pattern   VARCHAR(20),
    next_execution_date  DATE,
    end_date             DATE,
    scheduled_at         TIMESTAMP,
    processed_at         TIMESTAMP,
    failure_reason       VARCHAR(500),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_payer_account ON payments(payer_account_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_scheduled ON payments(scheduled_at) WHERE status = 'PENDING';

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    BIGINT NOT NULL,
    action       VARCHAR(50) NOT NULL,
    performed_by BIGINT,
    details      VARCHAR(1000),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
