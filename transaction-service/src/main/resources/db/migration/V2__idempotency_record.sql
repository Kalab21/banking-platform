-- Idempotency for money movement.
--
-- The client names each money-movement attempt with an Idempotency-Key. The row
-- created here is the record of that attempt: what was asked for, whether it
-- ran, and what the answer was. A repeat of the same request returns the stored
-- answer instead of moving money a second time.
--
-- The UNIQUE constraint is the mechanism, not the application code. Two
-- concurrent requests carrying the same key both attempt this INSERT; the
-- database lets exactly one through, and the loser resolves against the winner's
-- row. Any check-then-act in Java would have a window between the check and the
-- act.
CREATE TABLE idempotency_record (
    id               BIGSERIAL PRIMARY KEY,

    -- Opaque, client-generated. Never derived from the amount, the accounts or
    -- the time: two genuinely distinct transfers of the same amount between the
    -- same accounts must be able to both succeed.
    idempotency_key  VARCHAR(255) NOT NULL,

    operation        VARCHAR(20)  NOT NULL,

    -- SHA-256 over the caller and the normalised request fields. Replaying a key
    -- with a different body is a client bug and is rejected rather than served
    -- the earlier result, which would answer a question that was not asked.
    request_hash     VARCHAR(64)  NOT NULL,

    -- IN_PROGRESS | COMPLETED | FAILED | UNKNOWN. See IdempotencyStatus for what
    -- each one permits on a replay.
    status           VARCHAR(20)  NOT NULL,

    -- The response to replay. Populated only on COMPLETED: a failure is not
    -- cached as an answer.
    response_status  INTEGER,
    response_body    TEXT,

    -- The transaction reference the attempt produced, for reconciling a row
    -- against the transactions table without parsing the stored body.
    result_ref       VARCHAR(80),

    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_idempotency_record_key UNIQUE (idempotency_key)
);

-- Supports finding attempts that were left IN_PROGRESS or resolved UNKNOWN,
-- which are the rows an operator has to reconcile by hand.
CREATE INDEX idx_idempotency_record_status ON idempotency_record(status, created_at);
