package com.bankingplatform.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(name = "performed_by")
    private Long performedBy;

    /**
     * What kind of actor made the change: CUSTOMER, EMPLOYEE, ADMIN, or
     * SYSTEM when the platform acted on its own behalf.
     *
     * <p>Never null. A null {@code performedBy} beside {@code SYSTEM} means
     * "no user was involved"; beside a role it would mean the attribution was
     * lost, which is the thing this column exists to make visible.
     */
    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;


    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
