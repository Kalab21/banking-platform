package com.bankingplatform.user.repository;

import com.bankingplatform.user.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByPerformedByOrderByCreatedAtDesc(Long userId);
    List<AuditLog> findByEntityTypeAndEntityId(String entityType, Long entityId);
}
