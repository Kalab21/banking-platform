-- The processed-event guard's table.
--
-- Every other consuming service has had this since it started consuming.
-- application-service had not, because until it began confirming that products
-- were created it consumed nothing at all. The guard is in common-kafka and
-- looks for this table by name, so the first confirmation to arrive failed with
-- `relation "processed_event" does not exist`, retried four times and went to
-- the dead letter topic -- leaving the application at PROVISIONING for ever,
-- which is exactly the state the confirmation exists to move it out of.

CREATE TABLE processed_event (
    consumer_name VARCHAR(100) NOT NULL,
    event_id      VARCHAR(64)  NOT NULL,
    processed_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

-- The primary key is the control, not a lookup index. Kafka is at-least-once
-- and this service retries, so the same event id arrives again after a crash
-- between the business commit and the offset commit. A read-then-write check
-- would let two concurrent deliveries both decide they were first; a unique
-- key decides it once, in the database, and the losing insert affects no rows.
--
-- consumer_name is the handler rather than the service: the card and loan
-- confirmations are separate handlers over separate topics, and each has to act
-- on an event exactly once.
COMMENT ON TABLE processed_event IS
    $$Events this service has already acted on, keyed by consumer and event id.$$;

-- The claim table grows with every event consumed, and the primary key is no
-- help to a delete that selects by age. Without this the nightly prune degrades
-- into a sequential scan of the whole table.
CREATE INDEX idx_processed_event_processed_at
    ON processed_event (processed_at);
