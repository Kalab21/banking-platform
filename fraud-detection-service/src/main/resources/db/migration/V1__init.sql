CREATE TABLE fraud_alerts (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT        NOT NULL,
    user_id         BIGINT,
    alert_type      VARCHAR(50)   NOT NULL,
    risk_score      INT           NOT NULL,
    description     TEXT          NOT NULL,
    event_ref       VARCHAR(100),
    event_type      VARCHAR(50),
    amount          DECIMAL(19,2),
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',   -- OPEN, REVIEWED, RESOLVED, FALSE_POSITIVE
    reviewed_by     BIGINT,
    reviewed_at     TIMESTAMP,
    resolution_note TEXT,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE fraud_rules_audit (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT        NOT NULL,
    rule_name       VARCHAR(100)  NOT NULL,
    points_added    INT           NOT NULL,
    total_score     INT           NOT NULL,
    event_ref       VARCHAR(100),
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fraud_alerts_account ON fraud_alerts(account_id);
CREATE INDEX idx_fraud_alerts_status ON fraud_alerts(status) WHERE status = 'OPEN';
CREATE INDEX idx_fraud_alerts_created ON fraud_alerts(created_at);
