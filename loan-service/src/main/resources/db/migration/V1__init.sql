CREATE TABLE loans (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT NOT NULL,
    application_id        BIGINT,
    loan_type             VARCHAR(30)    NOT NULL,
    principal             DECIMAL(19,2)  NOT NULL,
    interest_rate         DECIMAL(5,2)   NOT NULL,
    term_months           INT            NOT NULL,
    monthly_payment       DECIMAL(19,2)  NOT NULL,
    total_interest        DECIMAL(19,2)  NOT NULL,
    remaining_balance     DECIMAL(19,2)  NOT NULL,
    disbursement_account_id BIGINT,
    disbursed_at          TIMESTAMP,
    next_payment_date     DATE,
    payments_made         INT            NOT NULL DEFAULT 0,
    status                VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    currency              VARCHAR(3)     NOT NULL DEFAULT 'USD',
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loans_user_id ON loans(user_id);
CREATE INDEX idx_loans_status  ON loans(status);

CREATE TABLE amortization_schedules (
    id               BIGSERIAL PRIMARY KEY,
    loan_id          BIGINT         NOT NULL REFERENCES loans(id),
    payment_number   INT            NOT NULL,
    due_date         DATE           NOT NULL,
    scheduled_payment DECIMAL(19,2) NOT NULL,
    principal_portion DECIMAL(19,2) NOT NULL,
    interest_portion  DECIMAL(19,2) NOT NULL,
    remaining_balance DECIMAL(19,2) NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    paid_at          TIMESTAMP,
    UNIQUE (loan_id, payment_number)
);

CREATE INDEX idx_amort_loan_id ON amortization_schedules(loan_id);
CREATE INDEX idx_amort_due     ON amortization_schedules(loan_id, due_date) WHERE status = 'PENDING';

CREATE TABLE loan_repayments (
    id              BIGSERIAL PRIMARY KEY,
    loan_id         BIGINT        NOT NULL REFERENCES loans(id),
    payment_ref     VARCHAR(36)   NOT NULL UNIQUE,
    amount          DECIMAL(19,2) NOT NULL,
    principal_paid  DECIMAL(19,2) NOT NULL,
    interest_paid   DECIMAL(19,2) NOT NULL,
    source_account_id BIGINT,
    payment_number  INT,
    is_early_payoff BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_repayments_loan_id ON loan_repayments(loan_id);

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  VARCHAR(50)   NOT NULL,
    entity_id    BIGINT        NOT NULL,
    action       VARCHAR(50)   NOT NULL,
    performed_by BIGINT,
    details      VARCHAR(1000),
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW()
);
