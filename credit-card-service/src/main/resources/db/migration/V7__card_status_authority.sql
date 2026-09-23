-- Card status, split by who may set it.
--
-- The vocabulary was ACTIVE, FROZEN, CLOSED, DEFAULT, and the service assigned
-- whichever one arrived in the request body. A cardholder could mark their own
-- card DEFAULT, or clear a default the bank had applied, by sending the word.
--
-- FROZEN carried no record of who froze it, so a customer's own precautionary
-- freeze and a block the bank imposed were the same state. There was no way to
-- let a customer lift the first without also letting them lift the second.
--
-- Existing rows are translated rather than guessed at:
--
--   FROZEN  -> CUSTOMER_FROZEN  the only freeze this platform could produce was
--                               one the cardholder asked for; nothing else set
--                               it, so that is what these rows are
--   DEFAULT -> DEFAULTED        the same state under a name that reads as one
--
-- ACTIVE and CLOSED keep their meaning and their name. SYSTEM_BLOCKED is new
-- and starts empty: nothing had the authority to apply it before now.

UPDATE credit_cards SET status = 'CUSTOMER_FROZEN' WHERE status = 'FROZEN';
UPDATE credit_cards SET status = 'DEFAULTED'       WHERE status = 'DEFAULT';

-- The database agrees with the enum, so a status cannot come back by a route
-- that misses the application code.
ALTER TABLE credit_cards
    ADD CONSTRAINT ck_credit_cards_status
    CHECK (status IN ('ACTIVE', 'CUSTOMER_FROZEN', 'SYSTEM_BLOCKED', 'DEFAULTED', 'CLOSED'));
