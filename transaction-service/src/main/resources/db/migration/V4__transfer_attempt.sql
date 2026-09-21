-- What a transfer was trying to do, written before it tries.
--
-- A transfer debits one account and credits another, both in account-service,
-- over HTTP. If the credit fails, the debit has already been applied in
-- another database and @Transactional here cannot undo it. That case is
-- detected and reported -- but until now nothing recorded it, because the
-- failure rolled back the very transaction that would have written the
-- record. account-service was short a hundred pounds and transaction-service
-- had no idea a transfer had ever been attempted.
--
-- This row is written in a transaction of its own, before the first leg, so
-- it survives the rollback of the transfer that created it. It is the only
-- durable evidence that the movement was attempted at all, and therefore the
-- only thing reconciliation can start from.
CREATE TABLE transfer_attempt (
    id                  BIGSERIAL PRIMARY KEY,

    -- The two references the legs are keyed by in account-service. They are
    -- minted before either call, so reconciliation can ask account-service
    -- what happened to each leg by the same key the leg was applied under.
    debit_ref           VARCHAR(80)  NOT NULL,
    credit_ref          VARCHAR(80)  NOT NULL,

    from_account_id     BIGINT       NOT NULL,
    to_account_id       BIGINT       NOT NULL,
    amount              DECIMAL(19,2) NOT NULL,
    currency            VARCHAR(3)   NOT NULL,

    -- STARTED        -- recorded, nothing applied yet
    -- DEBITED        -- the source was debited, the credit not yet attempted
    -- COMPLETED      -- both legs applied
    -- CREDIT_FAILED  -- the debit applied and the credit did not: the case
    --                   this table exists for
    -- RECONCILED     -- an operator or the reconciler established the truth
    status              VARCHAR(20)  NOT NULL,

    -- What reconciliation found when it asked account-service about each leg,
    -- so the answer is recorded rather than re-derived every time it is read.
    debit_applied       BOOLEAN,
    credit_applied      BOOLEAN,
    reconciled_at       TIMESTAMP,
    note                VARCHAR(500),

    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),

    -- One attempt per debit reference. The reference is minted once per
    -- transfer, so a retry under a new idempotency key is a new attempt and a
    -- replay of the same one is not.
    CONSTRAINT uq_transfer_attempt_debit_ref UNIQUE (debit_ref)
);

-- The reconciliation query: attempts that are not finished, oldest first.
-- Partial, so it holds only the rows an operator might have to act on rather
-- than every transfer the platform has ever made.
CREATE INDEX idx_transfer_attempt_unsettled
    ON transfer_attempt (created_at)
    WHERE status IN ('STARTED', 'DEBITED', 'CREDIT_FAILED');
