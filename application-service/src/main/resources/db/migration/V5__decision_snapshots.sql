-- What was decided, on what figures, under which policy.
--
-- Until now an application carried only its outcome and a line of reviewer
-- prose. The figures behind the decision were the customer's live profile, so
-- the reason an application was refused changed as the customer's score, income
-- and debts changed. An application refused in March for a ratio that is fine
-- in June looked as though it had been refused for nothing.
--
-- These rows are written once and never updated. A second decision on the same
-- application -- a referral a reviewer later approves -- is a new row, so the
-- history is the rows in order rather than one row overwritten.
--
-- No column here is a bureau's data. credit_score_at_decision is the synthetic
-- score the platform holds, and the thresholds it was measured against are
-- named by policy_version rather than assumed to be whatever the code says now.

CREATE TABLE decision_snapshots (
    id                           BIGSERIAL PRIMARY KEY,
    application_id               BIGINT        NOT NULL REFERENCES applications (id),
    policy_version               VARCHAR(32)   NOT NULL,
    decided_by                   VARCHAR(16)   NOT NULL,
    reviewer_id                  BIGINT,
    decision                     VARCHAR(16)   NOT NULL,
    credit_score_at_decision     INTEGER,
    kyc_status_at_decision       VARCHAR(32),
    annual_income_at_decision    NUMERIC(19, 2),
    monthly_debt_at_decision     NUMERIC(19, 2),
    dti_at_decision              NUMERIC(9, 4),
    ltv_at_decision              NUMERIC(9, 4),
    asset_value_at_decision      NUMERIC(19, 2),
    requested_amount_at_decision NUMERIC(19, 2),
    requested_term_at_decision   INTEGER,
    approved_amount              NUMERIC(19, 2),
    decided_at                   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- The same three answers the code models. A fourth would mean the database
    -- and the decision type had drifted apart.
    CONSTRAINT ck_decision_snapshots_decision
        CHECK (decision IN ('APPROVE', 'REJECT', 'REFER')),
    CONSTRAINT ck_decision_snapshots_decided_by
        CHECK (decided_by IN ('POLICY', 'REVIEWER')),

    -- A reviewer decision names the reviewer; the policy is not a person.
    CONSTRAINT ck_decision_snapshots_reviewer
        CHECK ((decided_by = 'REVIEWER' AND reviewer_id IS NOT NULL)
            OR (decided_by = 'POLICY' AND reviewer_id IS NULL)),

    -- A refusal approves nothing. Enforced here because an approved amount on a
    -- rejection would be read downstream as a product to create.
    CONSTRAINT ck_decision_snapshots_rejection_lends_nothing
        CHECK (decision <> 'REJECT' OR approved_amount IS NULL)
);

CREATE TABLE decision_snapshot_reasons (
    decision_snapshot_id BIGINT      NOT NULL REFERENCES decision_snapshots (id) ON DELETE CASCADE,
    reason_code          VARCHAR(48) NOT NULL
);

-- Every read is "the decisions on this application, in order".
CREATE INDEX ix_decision_snapshots_application
    ON decision_snapshots (application_id, decided_at);

CREATE INDEX ix_decision_snapshot_reasons_snapshot
    ON decision_snapshot_reasons (decision_snapshot_id);
