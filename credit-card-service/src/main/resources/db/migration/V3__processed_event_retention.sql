-- Pruning support for the processed-event guard.
--
-- The claim table grows with every event this service consumes, and the
-- primary key is on (consumer_name, event_id) — no help at all to a delete
-- that selects by age. Without this index the nightly prune degrades into a
-- sequential scan of the whole table, which is the opposite of what a
-- retention job is for.
--
-- Separate from V2 because V2 has already been applied; editing it would
-- change its checksum and Flyway would refuse to start against an existing
-- database.
CREATE INDEX idx_processed_event_processed_at
    ON processed_event (processed_at);
