package com.bankingplatform.transaction.idempotency;

import com.bankingplatform.common.observability.LogSafe;
import com.bankingplatform.transaction.model.IdempotencyRecord;
import com.bankingplatform.transaction.model.IdempotencyStatus;
import com.bankingplatform.transaction.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;

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
        Optional<IdempotencyOutcome> existing = repository.findOutcomeByIdempotencyKey(key);
        if (existing.isPresent()) {
            IdempotencyOutcome record = existing.get();

            boolean retryable = record.status() == IdempotencyStatus.FAILED
                    && record.requestHash().equals(fingerprint);
            if (!retryable) {
                return existing;
            }

            if (repository.reclaimFailed(record.id(), LocalDateTime.now()) == 1) {
                return Optional.empty();
            }
            // Another retry of the same failed attempt won the UPDATE. Re-read
            // rather than returning the stale FAILED row we started from.
            return repository.findOutcomeByIdempotencyKey(key);
        }

        repository.saveAndFlush(IdempotencyRecord.builder()
                .idempotencyKey(key)
                .operation(operation)
                .requestHash(fingerprint)
                .status(IdempotencyStatus.IN_PROGRESS)
                .build());
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyOutcome> find(String key) {
        return repository.findOutcomeByIdempotencyKey(key);
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
        log.error("Idempotency key {} resolved UNKNOWN: the account service was reached and the "
                + "outcome was never established. This record needs reconciliation.",
                LogSafe.value(key));
    }

    private void resolve(String key, IdempotencyStatus status,
                         Integer responseStatus, String responseBody, String resultRef) {
        int updated = repository.resolve(key, status, responseStatus, responseBody,
                resultRef, LocalDateTime.now());
        if (updated != 1) {
            // The row was not IN_PROGRESS, so something else already settled
            // it. Recording the second verdict over the first would be worse
            // than leaving it; log loudly instead.
            log.error("Idempotency key {} could not be resolved to {}: it is no longer IN_PROGRESS",
                    LogSafe.value(key), status);
        }
    }
}
