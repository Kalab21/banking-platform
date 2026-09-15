package com.bankingplatform.transaction.repository;

import com.bankingplatform.transaction.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
