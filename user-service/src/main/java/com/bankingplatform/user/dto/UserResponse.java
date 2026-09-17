package com.bankingplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long id;
    private String username;
    private String email;
    private String firstName;
    private String middleName;
    private String lastName;
    private String phone;

    /*
     * Profile captured at onboarding. This response is already owner-or-staff
     * only, which is what makes it acceptable to carry a date of birth and an
     * address at all.
     */
    private LocalDate dateOfBirth;
    private String streetAddress;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;

    /**
     * The last four digits of the Social Security number the customer supplied,
     * so the console can show them which number is on file. The full number is
     * never stored and so cannot be returned.
     */
    private String ssnLast4;

    /**
     * SUBMITTED once identity details are given. There is no verification
     * provider behind this system, so nothing here ever reads "verified".
     */
    private String identityStatus;
    private String role;
    private boolean enabled;
    private int creditScore;
    private String kycStatus;
    private boolean twoFactorEnabled;
    private LocalDateTime createdAt;
}
