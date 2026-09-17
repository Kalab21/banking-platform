package com.bankingplatform.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * The identity record captured at onboarding.
 *
 * <p>Separate from {@link User} on purpose. Everything else a customer gives us
 * is ordinary profile data; this is the part worth stealing. Keeping it in its
 * own entity means the object every profile read loads has nothing sensitive on
 * it to leak, and no mapping from {@code User} can expose it by accident.
 *
 * <p>Only the last four digits are here. The full number is validated for shape,
 * reduced, and dropped — see {@code UserServiceImpl.register}.
 */
@Entity
@Table(name = "customer_identity")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** The last four digits, for showing the customer which number they gave. */
    @Column(name = "ssn_last4", nullable = false, length = 4)
    private String ssnLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private IdentityStatus status = IdentityStatus.SUBMITTED;

    @CreationTimestamp
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;
}
