package com.bankingplatform.common.idempotency;

import com.bankingplatform.common.observability.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for idempotency records, deliberately kept in its own component.
 *
 * <p>Every method commits in a transaction of its own. That is the requirement
 * the whole design rests on: the claim has to be visible to other requests
 * <em>before</em> the money moves, and the verdict has to survive whatever
 * happened to the transaction that produced it. Sharing the caller's
 * transaction would make the claim invisible until commit — exactly when it is
 * too late to stop a second debit — and would roll the verdict back along with
 * the failure it is trying to record.
 *
 * <p>Written against JDBC rather than JPA. The record is not part of any
 * service's domain model, and an entity in a shared module would force every
 * service using it to override {@code @EntityScan} and
 * {@code @EnableJpaRepositories} — two annotations that replace Boot's
 * defaults and are easy to get subtly wrong. It also removes the persistence
 * context from the path entirely, which is what made the original read the
 * outcome as a projection: with {@code open-in-view}, a duplicate polling for
 * the original's verdict kept being handed the same stale instance it had
 * already read, and waited out its whole budget instead of collecting the
 * result.
 */
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private static final String SELECT = """
            SELECT id, idempotency_key, operation, request_hash, status,
                   response_status, response_body, result_ref
            FROM idempotency_record
            WHERE idempotency_key = ?
            """;

    private static final String INSERT = """
            INSERT INTO idempotency_record
                (idempotency_key, operation, request_hash, status, created_at, updated_at)
            VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)
            """;

    // Conditional on FAILED, which is the whole point: of two concurrent
    // retries of the same failed attempt, exactly one sees a row count of 1.
    // Reading the status and then writing it would let both through.
    private static final String RECLAIM_FAILED = """
            UPDATE idempotency_record
               SET status = 'IN_PROGRESS', response_status = NULL, response_body = NULL,
                   result_ref = NULL, updated_at = ?
             WHERE id = ? AND status = 'FAILED'
            """;

    // Conditional on IN_PROGRESS so a late or duplicated completion cannot
    // overwrite a verdict already recorded.
    private static final String RESOLVE = """
            UPDATE idempotency_record
               SET status = ?, response_status = ?, response_body = ?, result_ref = ?,
                   updated_at = ?
             WHERE idempotency_key = ? AND status = 'IN_PROGRESS'
            """;

    private static final RowMapper<IdempotencyOutcome> AS_OUTCOME = (rs, row) -> new IdempotencyOutcome(
            rs.getLong("id"),
            rs.getString("idempotency_key"),
            rs.getString("operation"),
            rs.getString("request_hash"),
            IdempotencyStatus.valueOf(rs.getString("status")),
            rs.getObject("response_status", Integer.class),
            rs.getString("response_body"),
            rs.getString("result_ref"));

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempt to take ownership of a key.
     *
     * @return empty when this caller now owns the key and must execute;
     *         otherwise the record to resolve against.
     * @throws org.springframework.dao.DataIntegrityViolationException when
     *         another request inserted the same key first. The read below is an
     *         optimisation; the unique constraint is the actual arbiter.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyOutcome> claim(String key, String operation, String fingerprint) {
        Optional<IdempotencyOutcome> existing = read(key);
        if (existing.isPresent()) {
            IdempotencyOutcome record = existing.get();

            boolean retryable = record.status() == IdempotencyStatus.FAILED
                    && record.requestHash().equals(fingerprint);
            if (!retryable) {
                return existing;
            }

            if (jdbc.update(RECLAIM_FAILED, Timestamp.valueOf(LocalDateTime.now()), record.id()) == 1) {
                return Optional.empty();
            }
            // Another retry of the same failed attempt won the UPDATE. Re-read
            // rather than returning the stale FAILED row we started from.
            return read(key);
        }

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update(INSERT, key, operation, fingerprint, now, now);
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyOutcome> find(String key) {
        return read(key);
    }

    /** The money moved. Store the response so a replay can return it verbatim. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, int responseStatus, String responseBody, String resultRef) {
        resolve(key, IdempotencyStatus.COMPLETED, responseStatus, responseBody, resultRef);
    }

    /**
     * The attempt was refused before anything was applied. No response is
     * stored: the key is released so the client may retry it, and a cached
     * failure would prevent that.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String key) {
        resolve(key, IdempotencyStatus.FAILED, null, null, null);
    }

    /** The attempt's effect on the balance is not knowable. The key stays spent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(String key) {
        resolve(key, IdempotencyStatus.UNKNOWN, null, null, null);
        // The key is an opaque value the client chose. The guard restricts it
        // to a narrow alphabet before this point, but the restriction lives in
        // the caller and this is the line that would be forged, so the value is
        // neutralised where it is used rather than where it happens to arrive.
        log.error("Idempotency key {} resolved UNKNOWN: the downstream call was made and the "
                        + "outcome was never established. This record needs reconciliation.",
                LogSafe.value(key));
    }

    private Optional<IdempotencyOutcome> read(String key) {
        List<IdempotencyOutcome> found = jdbc.query(SELECT, AS_OUTCOME, key);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private void resolve(String key, IdempotencyStatus status,
                         Integer responseStatus, String responseBody, String resultRef) {
        int updated = jdbc.update(RESOLVE, status.name(), responseStatus, responseBody, resultRef,
                Timestamp.valueOf(LocalDateTime.now()), key);
        if (updated != 1) {
            // The row was not IN_PROGRESS, so something else already settled
            // it. Recording the second verdict over the first would be worse
            // than leaving it; log loudly instead.
            log.error("Idempotency key {} could not be resolved to {}: it is no longer IN_PROGRESS",
                    LogSafe.value(key), status);
        }
    }
}
