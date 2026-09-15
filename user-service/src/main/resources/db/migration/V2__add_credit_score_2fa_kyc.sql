-- Credit score fields
ALTER TABLE users ADD COLUMN credit_score INT NOT NULL DEFAULT 700;
ALTER TABLE users ADD COLUMN credit_score_updated_at TIMESTAMP;

-- KYC fields
ALTER TABLE users ADD COLUMN kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE users ADD COLUMN kyc_completed_at TIMESTAMP;

-- 2FA fields
ALTER TABLE users ADD COLUMN two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN two_factor_secret VARCHAR(100);

-- Credit score history
CREATE TABLE credit_score_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    old_score INT NOT NULL,
    new_score INT NOT NULL,
    change_reason VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- KYC documents
CREATE TABLE kyc_documents (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    document_type VARCHAR(30) NOT NULL,   -- PASSPORT, DRIVERS_LICENSE, NATIONAL_ID, PROOF_OF_ADDRESS, SSN
    document_ref VARCHAR(100) NOT NULL,   -- external storage reference / file name
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',  -- SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED
    rejection_reason VARCHAR(255),
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Audit log
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id BIGINT,
    action VARCHAR(30) NOT NULL,   -- CREATE, UPDATE, DELETE, LOGIN, LOGOUT, FAILED_LOGIN
    performed_by BIGINT,
    details TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
