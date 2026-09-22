package com.bankingplatform.application.repository;

import com.bankingplatform.application.model.DecisionSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionSnapshotRepository extends JpaRepository<DecisionSnapshot, Long> {

    /** Every decision taken on an application, oldest first. */
    List<DecisionSnapshot> findByApplicationIdOrderByDecidedAtAsc(Long applicationId);
}
