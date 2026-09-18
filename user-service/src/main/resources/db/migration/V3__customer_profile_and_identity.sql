-- Customer profile captured during onboarding, and the identity record that
-- accompanies it.
--
-- Two tables on purpose. Name, date of birth and address are ordinary profile
-- attributes that the user domain owns, so they live on `users`. The Social
-- Security number is not: it is the one field here that would be worth stealing,
-- so it is kept out of the row that every profile read already loads and out of
-- the entity that UserResponse maps from. Making it a separate entity means a
-- careless `@Mapping` cannot leak it by accident — there is nothing on `User` to
-- leak.

-- ---------------------------------------------------------------- profile

-- Every column is nullable, which is deliberate rather than lax. The table
-- already holds demo and seeded accounts created before onboarding existed, and
-- a NOT NULL column with no sensible default would make this migration fail on
-- any populated database. Registration requires these fields at the request
-- validation layer instead, so new customers cannot skip them while existing
-- rows stay valid.
ALTER TABLE users ADD COLUMN middle_name    VARCHAR(50);
ALTER TABLE users ADD COLUMN date_of_birth  DATE;
ALTER TABLE users ADD COLUMN street_address VARCHAR(120);
ALTER TABLE users ADD COLUMN address_line_2 VARCHAR(60);
ALTER TABLE users ADD COLUMN city           VARCHAR(60);

-- Two-letter USPS abbreviation, normalised to upper case before it is written.
-- VARCHAR rather than CHAR: CHAR pads to width, and the padded value then fails
-- Hibernate's schema validation against a String mapping.
ALTER TABLE users ADD COLUMN state          VARCHAR(2);

-- Five digits, or five-four with the hyphen kept.
ALTER TABLE users ADD COLUMN postal_code    VARCHAR(10);

-- ---------------------------------------------------------------- identity

CREATE TABLE customer_identity (
    id           BIGSERIAL PRIMARY KEY,

    user_id      BIGINT       NOT NULL UNIQUE REFERENCES users (id),

    -- The last four digits, and nothing else. The number the customer typed is
    -- validated for shape, reduced to these four characters and then dropped;
    -- it is never written to a column, a log or a response. Four digits alone
    -- cannot be worked back into the original.
    --
    -- There is deliberately no hash of the full number either. A Social
    -- Security number has fewer than a billion possible values, so an unkeyed
    -- digest of one is recoverable by exhaustive search and would be a false
    -- reassurance rather than a protection. A keyed HMAC would be defensible,
    -- but only if something actually needed to match identities — nothing here
    -- does.
    ssn_last4    VARCHAR(4)   NOT NULL,

    -- SUBMITTED until a real verification process says otherwise. This system
    -- has no identity-verification provider, so nothing here may claim an
    -- identity has been verified; passing a format check is not verification.
    status       VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',

    submitted_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customer_identity_user ON customer_identity (user_id);
