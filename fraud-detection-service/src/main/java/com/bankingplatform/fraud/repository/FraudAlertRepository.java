package com.bankingplatform.fraud.repository;

import com.bankingplatform.fraud.model.AlertStatus;
import com.bankingplatform.fraud.model.FraudAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    Page<FraudAlert> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    List<FraudAlert> findByStatusOrderByCreatedAtDesc(AlertStatus status);

    long countByAccountIdAndStatus(Long accountId, AlertStatus status);
}
