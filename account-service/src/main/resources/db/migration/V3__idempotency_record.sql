-- Idempotency for the internal balance endpoint.
--
-- /internal/accounts/{id}/balance is the one place on this platform where a
-- balance actually changes. Every other service that moves money -- transfer,
-- card payment, cash advance, loan repayment and disbursement -- ends up
-- here, over HTTP.
--
-- A call that times out tells the caller nothing about whether the balance
-- changed. Without a key, the only safe response to that is never to retry,
-- and the only safe response to a retry is to apply it again. Both are wrong:
-- the first strands money movement that may not have happened, the second
-- debits twice.
--
-- The key makes the answer knowable. A repeat of the same call returns the
-- balance the first one produced, and applies nothing.
--
-- The UNIQUE constraint is the mechanism, not the application code. Two
-- concurrent requests carrying the same key both attempt the INSERT; the
-- database lets exactly one through, and the loser resolves against the
-- winner's row.
--
-- Identical in shape to the tables in transaction-service, credit-card-service
-- and loan-service, because the store that reads and writes it is shared.
CREATE TABLE idempotency_record (
    id               BIGSERIAL PRIMARY KEY,

    -- Opaque to this service. Its callers derive it from the transaction or
    -- payment reference the movement belongs to, so a retry of that movement
    -- carries the same key and a new movement does not.
    idempotency_key  VARCHAR(255) NOT NULL,

    operation        VARCHAR(20)  NOT NULL,

    -- SHA-256 over the caller and the normalised request fields. Replaying a
    -- key with a different body is a caller bug and is rejected rather than
    -- served the earlier result.
    request_hash     VARCHAR(64)  NOT NULL,

    -- IN_PROGRESS | COMPLETED | FAILED | UNKNOWN.
    status           VARCHAR(20)  NOT NULL,

    -- The response to replay. Populated only on COMPLETED.
    response_status  INTEGER,
    response_body    TEXT,

    result_ref       VARCHAR(80),

    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_idempotency_record_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_idempotency_record_status ON idempotency_record(status, created_at);
