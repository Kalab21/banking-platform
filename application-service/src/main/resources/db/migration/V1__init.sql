CREATE TABLE applications (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    application_type    VARCHAR(30) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_amount    DECIMAL(19,2),
    approved_amount     DECIMAL(19,2),
    currency            VARCHAR(3) NOT NULL DEFAULT 'USD',
    term_months         INT,
    purpose             VARCHAR(500),
    credit_score_at_apply INT,
    reviewer_notes      VARCHAR(1000),
    product_id          BIGINT,
    applied_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_applications_user_id ON applications(user_id);
CREATE INDEX idx_applications_status ON applications(status);
CREATE INDEX idx_applications_type ON applications(application_type);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id   BIGINT NOT NULL,
    action      VARCHAR(50) NOT NULL,
    performed_by BIGINT,
    details     VARCHAR(1000),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
