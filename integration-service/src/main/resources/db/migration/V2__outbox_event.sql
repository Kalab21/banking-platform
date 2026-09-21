-- The transactional outbox.
--
-- A domain write and its publication have to commit together. kafkaTemplate
-- .send is asynchronous, so it returns before the broker has acknowledged
-- anything and a caught exception is not evidence of anything; the database
-- transaction could commit while the event never reached Kafka. A row here
-- commits with the business change or not at all, and the relay sends it
-- afterwards.
CREATE TABLE outbox_event (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(80)  NOT NULL,
    topic         VARCHAR(120) NOT NULL,
    partition_key VARCHAR(120) NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    published_at  TIMESTAMP,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(500)
);

-- id is the ordering the relay sends in, so it has to be monotonic per row
-- insert rather than derived from anything the caller controls.

-- One publication, once. A caller whose transaction is retried must not queue
-- the same event twice; the id is minted with the event, so a repeat is the
-- same publication rather than a new one.
ALTER TABLE outbox_event ADD CONSTRAINT ux_outbox_event_event_id UNIQUE (event_id);

-- The relay scans for unsent work on every tick, and once the table has any
-- history a full scan is almost all rows it does not want. Partial, so the
-- index holds only what is still pending and stays small no matter how much
-- has been published.
CREATE INDEX idx_outbox_event_pending
    ON outbox_event (partition_key, id)
    WHERE published_at IS NULL;

-- Supports pruning of rows the relay has already sent.
CREATE INDEX idx_outbox_event_published_at
    ON outbox_event (published_at)
    WHERE published_at IS NOT NULL;
