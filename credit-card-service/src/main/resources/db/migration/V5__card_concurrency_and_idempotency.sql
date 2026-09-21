-- Two separate holes in card money movement, closed together because both are
-- about the same thing: doing an operation exactly once.

-- 1. Concurrency.
--
-- purchase, cashAdvance and makePayment all read the card, compute a new
-- balance in Java and write it back. Nothing held the row in between, so two
-- purchases arriving together both read the same availableCredit, both passed
-- the credit-limit check, and both wrote -- leaving the card over its limit by
-- the smaller of the two amounts, with no record that anything went wrong.
--
-- The fix is a pessimistic row lock taken at read time, so the check and the
-- write are one atomic act. This column is defence in depth behind it: any
-- path that updates a card without taking the lock will fail on the version
-- rather than silently overwrite a concurrent change.
ALTER TABLE credit_cards ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 2. Idempotency.
--
-- A retried purchase charged the card twice. The client names each attempt
-- with an Idempotency-Key and this table is the record of that attempt: what
-- was asked for, whether it ran, and what the answer was.
--
-- The UNIQUE constraint is the mechanism, not the application code. Two
-- concurrent requests carrying the same key both attempt the INSERT; the
-- database lets exactly one through, and the loser resolves against the
-- winner's row. Any check-then-act in Java would have a window between them.
--
-- Identical in shape to transaction-service's table, because the store that
-- reads and writes it is shared.
CREATE TABLE idempotency_record (
    id               BIGSERIAL PRIMARY KEY,

    -- Opaque, client-generated. Never derived from the amount or the card:
    -- two genuinely distinct purchases of the same amount on the same card
    -- must both be able to succeed.
    idempotency_key  VARCHAR(255) NOT NULL,

    operation        VARCHAR(20)  NOT NULL,

    -- SHA-256 over the caller and the normalised request fields. Replaying a
    -- key with a different body is a client bug and is rejected rather than
    -- served the earlier result, which would answer a question nobody asked.
    request_hash     VARCHAR(64)  NOT NULL,

    -- IN_PROGRESS | COMPLETED | FAILED | UNKNOWN.
    status           VARCHAR(20)  NOT NULL,

    -- The response to replay. Populated only on COMPLETED: a failure is not
    -- cached as an answer.
    response_status  INTEGER,
    response_body    TEXT,

    -- The transaction reference the attempt produced, for reconciling a row
    -- against the card transactions without parsing the stored body.
    result_ref       VARCHAR(80),

    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_idempotency_record_key UNIQUE (idempotency_key)
);

-- Supports finding attempts left IN_PROGRESS or resolved UNKNOWN, which are
-- the rows an operator has to reconcile by hand.
CREATE INDEX idx_idempotency_record_status ON idempotency_record(status, created_at);
