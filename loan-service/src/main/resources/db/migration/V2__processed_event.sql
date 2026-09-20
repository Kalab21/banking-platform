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

-- Defence in depth for the one consumer that creates a financial product.
--
-- The processed-event guard stops a redelivery reaching createLoan at all.
-- This stops a second loan existing even if it did: a different code path, a
-- manual replay from the dead letter topic, or a future consumer that forgets
-- the guard. Two loans for one approved application is not a state this
-- platform should be able to represent.
--
-- Partial, because application_id is nullable: a loan created by any route
-- other than an application has no id to be unique on.
CREATE UNIQUE INDEX ux_loans_application_id
    ON loans (application_id)
    WHERE application_id IS NOT NULL;
