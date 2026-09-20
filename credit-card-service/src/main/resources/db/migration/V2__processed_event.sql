CREATE TABLE processed_event (
    consumer_name VARCHAR(100)             NOT NULL,
    event_id      VARCHAR(64)              NOT NULL,
    processed_at  TIMESTAMP                NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

-- The primary key is the control, not a lookup index. Kafka is at-least-once
-- and this service retries, so the same event id arrives again after a crash
-- between the business commit and the offset commit. A read-then-write check
-- would let two concurrent deliveries both decide they were first; a unique
-- key decides it once, in the database, and the losing insert simply affects
-- no rows.
--
-- consumer_name is the handler rather than the service: several handlers here
-- consume the same topic, and each has to act on an event exactly once.
COMMENT ON TABLE processed_event IS
    $$Events this service has already acted on, keyed by consumer and event id.$$;

-- Defence in depth, for the same reason as the loan equivalent: one approved
-- application must not be able to produce two cards, whatever route a
-- duplicate arrives by. Partial because application_id is nullable.
CREATE UNIQUE INDEX ux_credit_cards_application_id
    ON credit_cards (application_id)
    WHERE application_id IS NOT NULL;
