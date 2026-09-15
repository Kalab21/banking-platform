CREATE TABLE external_transfers (
    id                  BIGSERIAL PRIMARY KEY,
    transfer_ref        VARCHAR(36)   NOT NULL UNIQUE,
    transfer_type       VARCHAR(10)   NOT NULL,
    from_account_id     BIGINT        NOT NULL,
    beneficiary_name    VARCHAR(100)  NOT NULL,
    beneficiary_account VARCHAR(34),
    routing_number      VARCHAR(9),
    swift_code          VARCHAR(11),
    iban                VARCHAR(34),
    bank_name           VARCHAR(100),
    bank_country        VARCHAR(3),
    amount              DECIMAL(19,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    purpose             VARCHAR(255),
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    estimated_arrival   DATE,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_log (
    id            BIGSERIAL PRIMARY KEY,
    entity_type   VARCHAR(50)  NOT NULL,
    entity_id     BIGINT,
    action        VARCHAR(30)  NOT NULL,
    performed_by  BIGINT,
    details       TEXT,
    ip_address    VARCHAR(45),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
