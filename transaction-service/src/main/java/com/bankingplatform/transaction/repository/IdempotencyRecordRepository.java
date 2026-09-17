package com.bankingplatform.transaction.repository;

import com.bankingplatform.transaction.model.IdempotencyRecord;
import com.bankingplatform.transaction.model.IdempotencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * Take ownership of a key whose previous attempt is known to have moved no
     * money, so the client may retry under the same key.
     *
     * <p>The {@code status = FAILED} predicate is the whole point: it makes the
     * hand-over a single conditional UPDATE, so of two concurrent retries
     * exactly one sees a row count of 1 and proceeds. Reading the status and
     * then writing it would let both through.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IdempotencyRecord r
               SET r.status = com.bankingplatform.transaction.model.IdempotencyStatus.IN_PROGRESS,
                   r.responseStatus = NULL,
                   r.responseBody = NULL,
                   r.resultRef = NULL,
                   r.updatedAt = :now
             WHERE r.id = :id
               AND r.status = com.bankingplatform.transaction.model.IdempotencyStatus.FAILED
            """)
    int reclaimFailed(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Resolve a claimed key. Conditional on {@code IN_PROGRESS} so that a late
     * or duplicated completion cannot overwrite a verdict already recorded.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IdempotencyRecord r
               SET r.status = :status,
                   r.responseStatus = :responseStatus,
                   r.responseBody = :responseBody,
                   r.resultRef = :resultRef,
                   r.updatedAt = :now
             WHERE r.idempotencyKey = :key
               AND r.status = com.bankingplatform.transaction.model.IdempotencyStatus.IN_PROGRESS
            """)
    int resolve(@Param("key") String key,
                @Param("status") IdempotencyStatus status,
                @Param("responseStatus") Integer responseStatus,
                @Param("responseBody") String responseBody,
                @Param("resultRef") String resultRef,
                @Param("now") LocalDateTime now);
}
