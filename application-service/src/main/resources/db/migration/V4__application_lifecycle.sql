-- The application lifecycle, told honestly.
--
-- The old vocabulary was PENDING, UNDER_REVIEW, APPROVED, REJECTED, CANCELLED
-- and DISBURSED, and every approved application finished at DISBURSED whatever
-- it had been for. A credit card is not disbursed. Worse, DISBURSED was
-- written the moment the approval was published, so an application said the
-- product had been handed over when all that had happened was a message being
-- sent.
--
-- Existing rows are translated rather than guessed at:
--
--   PENDING   -> SUBMITTED     the same thing under a clearer name
--   APPROVED  -> UNDER_REVIEW  a transient state in the old code; nothing
--                              downstream was confirmed, so review is where
--                              these honestly sit
--   DISBURSED -> PROVISIONED   only where product_id names a real product,
--                              which in practice means the deposit accounts
--   DISBURSED -> PROVISIONING  everywhere else: a credit product was asked
--                              for, and whether it exists is not recorded here
--
-- REJECTED and CANCELLED keep their meaning and their name.

UPDATE applications SET status = 'SUBMITTED' WHERE status = 'PENDING';
UPDATE applications SET status = 'UNDER_REVIEW' WHERE status = 'APPROVED';
UPDATE applications SET status = 'PROVISIONED'
 WHERE status = 'DISBURSED' AND product_id IS NOT NULL AND product_id > 0;
UPDATE applications SET status = 'PROVISIONING' WHERE status = 'DISBURSED';

-- -1 was returned by provisioning as a placeholder for "downstream not wired"
-- and then stored in product_id as though it were an identifier. An
-- application therefore claimed to have a product, and pointed at one that
-- cannot exist. Null is what was actually known.
UPDATE applications SET product_id = NULL WHERE product_id IS NOT NULL AND product_id <= 0;

-- A status outside the lifecycle cannot be reached through the application
-- code, which routes every write through ApplicationTransitions. The
-- constraint is for everything else: a migration, a fixture, a repair script
-- run at three in the morning.
ALTER TABLE applications
    ADD CONSTRAINT ck_applications_status CHECK (status IN (
        'SUBMITTED', 'UNDER_REVIEW', 'MANUAL_REVIEW', 'OFFERED', 'REJECTED',
        'ACCEPTED', 'DECLINED', 'PROVISIONING', 'PROVISIONED', 'CANCELLED'));

-- An identifier is positive. This says so, so the old placeholder cannot come
-- back by a route that misses the application code.
ALTER TABLE applications
    ADD CONSTRAINT ck_applications_product_id_positive CHECK (product_id IS NULL OR product_id > 0);

-- What the applicant stated about their own finances, as at submission.
--
-- These live on the application rather than being read from the customer's
-- profile at decision time, because a decision has to remain explainable
-- against what was said when it was made. A customer whose income changes
-- next year must not retrospectively change why they were approved.
ALTER TABLE applications ADD COLUMN annual_income NUMERIC(19, 2);
ALTER TABLE applications ADD COLUMN monthly_debt_obligations NUMERIC(19, 2);
ALTER TABLE applications ADD COLUMN asset_value NUMERIC(19, 2);
ALTER TABLE applications ADD COLUMN down_payment NUMERIC(19, 2);

COMMENT ON COLUMN applications.asset_value IS
    'Value of the thing the lending is secured on: the vehicle for an auto loan, the property for a mortgage.';

-- Staff review lists applications by status, and the customer views their own.
CREATE INDEX IF NOT EXISTS idx_applications_status_applied
    ON applications (status, applied_at DESC);
