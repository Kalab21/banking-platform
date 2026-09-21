package com.bankingplatform.transaction.repository;

import com.bankingplatform.transaction.model.TransferAttempt;
import com.bankingplatform.transaction.model.TransferAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransferAttemptRepository extends JpaRepository<TransferAttempt, Long> {

    Optional<TransferAttempt> findByDebitRef(String debitRef);

    /**
     * Attempts that have not settled, oldest first.
     *
     * <p>The age bound keeps a transfer that is merely in flight out of the
     * list. A transfer takes milliseconds; one still unsettled minutes later
     * is one that stopped, not one that is slow.
     */
    @Query("""
            SELECT a FROM TransferAttempt a
             WHERE a.status IN :statuses
               AND a.createdAt < :olderThan
             ORDER BY a.createdAt
            """)
    List<TransferAttempt> findUnsettled(@Param("statuses") List<TransferAttemptStatus> statuses,
                                        @Param("olderThan") LocalDateTime olderThan);

    List<TransferAttempt> findByStatusOrderByCreatedAtDesc(TransferAttemptStatus status);
}
