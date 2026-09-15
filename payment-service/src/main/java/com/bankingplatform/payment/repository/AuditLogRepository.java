package com.bankingplatform.payment.repository;

import com.bankingplatform.payment.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
