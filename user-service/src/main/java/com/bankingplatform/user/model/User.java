package com.bankingplatform.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(name = "middle_name", length = 50)
    private String middleName;

    @Column(length = 20)
    private String phone;

    /*
     * Profile captured at onboarding. Nullable at the database level because
     * this table already holds accounts created before onboarding existed;
     * RegisterRequest requires them, so a new customer cannot skip them while a
     * legacy row stays valid.
     */

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "street_address", length = 120)
    private String streetAddress;

    @Column(name = "address_line_2", length = 60)
    private String addressLine2;

    @Column(length = 60)
    private String city;

    /** Two-letter USPS abbreviation, stored upper case. */
    @Column(length = 2)
    private String state;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.CUSTOMER;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    // Credit score
    @Column(name = "credit_score", nullable = false)
    @Builder.Default
    private int creditScore = 700;

    @Column(name = "credit_score_updated_at")
    private LocalDateTime creditScoreUpdatedAt;

    // KYC
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    @Column(name = "kyc_completed_at")
    private LocalDateTime kycCompletedAt;

    // 2FA
    @Column(name = "two_factor_enabled", nullable = false)
    @Builder.Default
    private boolean twoFactorEnabled = false;

    @Column(name = "two_factor_secret", length = 100)
    private String twoFactorSecret;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
