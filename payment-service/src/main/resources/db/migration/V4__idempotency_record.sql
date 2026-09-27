-- Idempotency for payment creation.
--
-- POST /api/payments took no key, and each request minted a new payment
-- reference, so a retried request after a lost response paid a second time.
-- Identical in shape to the tables in transaction-, loan- and
-- credit-card-service, because the store that reads and writes it is shared.
CREATE TABLE idempotency_record (
    id               BIGSERIAL PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL,
    operation        VARCHAR(20)  NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    response_status  INTEGER,
    response_body    TEXT,
    result_ref       VARCHAR(80),
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_idempotency_record_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_idempotency_record_status ON idempotency_record(status, created_at);
