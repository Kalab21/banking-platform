-- Who performed the change, as a positive statement.
--
-- performed_by has been nullable since the beginning and null at almost every
-- call site: the audit log recorded what happened and not who did it, which
-- is most of the reason to keep one.
--
-- Null is also ambiguous, and that is the harder half. A scheduled job and a
-- Kafka listener genuinely have no user behind them, so null is correct for
-- those -- and indistinguishable from a customer action whose identity was
-- simply never recorded. An auditor cannot tell "the platform did this" from
-- "we do not know".
--
-- actor_type answers that directly. CUSTOMER, EMPLOYEE or ADMIN names the
-- kind of person who acted, with performed_by carrying their id; SYSTEM says
-- the platform acted on its own behalf, and then a null performed_by means
-- what it says.
--
-- Backfilled as UNKNOWN rather than guessed. The existing rows were written
-- without this distinction and inventing one for them would be worse than
-- admitting it.
ALTER TABLE audit_log ADD COLUMN actor_type VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';

-- The audit question is almost always "what did this person do", so the
-- lookup is by actor rather than by row.
CREATE INDEX idx_audit_log_actor ON audit_log (actor_type, performed_by, created_at);
