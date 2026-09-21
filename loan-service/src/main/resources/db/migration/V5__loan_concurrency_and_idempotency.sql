-- Two holes in loan repayment, closed together because both are about the
-- same thing: applying a payment exactly once.

-- 1. Concurrency.
--
-- makeRepayment, earlyPayoff and disburseLoan all read the loan, compute a new
-- remaining balance in Java, and write it back. Nothing held the row in
-- between, so two repayments arriving together both read the same balance and
-- both wrote -- the loan ends up reduced by one payment while two were taken
-- from the customer's account, and paymentsMade counts both. Worse, both
-- selected the same "next unpaid" instalment and both marked it settled, so
-- one scheduled payment absorbs two.
--
-- The fix is a pessimistic row lock taken at read time, so selecting the
-- instalment, doing the arithmetic and writing are one atomic act. This
-- column is defence in depth behind it: any path that updates a loan without
-- taking the lock fails on the version rather than silently overwriting a
-- concurrent change.
ALTER TABLE loans ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 2. Idempotency.
--
-- A retried repayment was applied twice: the account was debited again and
-- another instalment marked paid. The client names each attempt with an
-- Idempotency-Key, and this table is the record of that attempt -- what was
-- asked for, whether it ran, and what the answer was.
--
-- The UNIQUE constraint is the mechanism, not the application code. Two
-- concurrent requests carrying the same key both attempt the INSERT; the
-- database lets exactly one through, and the loser resolves against the
-- winner's row. Any check-then-act in Java would have a window between them.
--
-- Identical in shape to the tables in transaction-service and
-- credit-card-service, because the store that reads and writes it is shared.
CREATE TABLE idempotency_record (
    id               BIGSERIAL PRIMARY KEY,

    -- Opaque, client-generated. Never derived from the amount or the loan:
    -- two genuinely distinct repayments of the same amount on the same loan
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

    -- The payment reference the attempt produced, for reconciling a row
    -- against the repayments without parsing the stored body.
    result_ref       VARCHAR(80),

    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_idempotency_record_key UNIQUE (idempotency_key)
);

-- Supports finding attempts left IN_PROGRESS or resolved UNKNOWN, which are
-- the rows an operator has to reconcile by hand.
CREATE INDEX idx_idempotency_record_status ON idempotency_record(status, created_at);
