-- What Northbank offered, and what the customer did about it.
--
-- Until now an approval went straight to provisioning and each product service
-- worked out its own terms. loan-service picked the term from a switch on
-- product type, so a customer who asked for twelve months was written a
-- forty-eight month loan, and nothing recorded that they had been offered
-- anything at all. Card tier, limit and rate were decided the same way, in
-- credit-card-service, from a credit score the application had already been
-- decided on.
--
-- The terms live here, set once. Acceptance moves the status and nothing else,
-- so there is no route by which an accepted offer differs from the offer that
-- was made.

CREATE TABLE offers (
    id                   BIGSERIAL PRIMARY KEY,
    application_id       BIGINT       NOT NULL REFERENCES applications (id),
    decision_snapshot_id BIGINT       REFERENCES decision_snapshots (id),
    user_id              BIGINT       NOT NULL,
    product_type         VARCHAR(32)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,

    approved_amount      NUMERIC(19, 2),
    apr                  NUMERIC(9, 4),
    term_months          INTEGER,
    monthly_payment      NUMERIC(19, 2),
    credit_limit         NUMERIC(19, 2),
    card_tier            VARCHAR(16),
    currency             VARCHAR(3)   NOT NULL DEFAULT 'USD',

    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at           TIMESTAMP,
    accepted_at          TIMESTAMP,
    declined_at          TIMESTAMP,
    version              BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_offers_status
        CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED')),

    -- A timestamp is the evidence that something happened. A status that claims
    -- it happened without one, or a timestamp without the status, means the two
    -- have drifted.
    CONSTRAINT ck_offers_accepted_has_timestamp
        CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CONSTRAINT ck_offers_declined_has_timestamp
        CHECK ((status = 'DECLINED') = (declined_at IS NOT NULL)),

    -- An offer is one or the other, never both.
    CONSTRAINT ck_offers_not_both
        CHECK (accepted_at IS NULL OR declined_at IS NULL),

    -- A loan is priced by rate and term; a card by tier and limit. A row
    -- carrying both is not a product this bank offers.
    CONSTRAINT ck_offers_shape
        CHECK ((card_tier IS NULL AND credit_limit IS NULL)
            OR (term_months IS NULL AND monthly_payment IS NULL))
);

-- At most one live offer per application. A superseded offer must be closed
-- before another is made, so a customer can never be looking at two open offers
-- and accept the wrong one.
CREATE UNIQUE INDEX ux_offers_one_open_per_application
    ON offers (application_id)
    WHERE status = 'OFFERED';

CREATE INDEX ix_offers_user ON offers (user_id, created_at);
