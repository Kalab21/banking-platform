package com.bankingplatform.fraud.repository;

import com.bankingplatform.fraud.model.FraudRulesAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudRulesAuditRepository extends JpaRepository<FraudRulesAudit, Long> {
}
