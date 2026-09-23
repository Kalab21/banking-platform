package com.bankingplatform.user.demo;

import com.bankingplatform.user.model.KycStatus;
import com.bankingplatform.user.model.Role;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;

/**
 * A staff account for the local demonstration stack, and nothing else.
 *
 * <p>Several controls on this platform can only be exercised by somebody who is
 * not the customer: reviewing an application that underwriting referred,
 * completing a KYC check, and simulating a card purchase. Until now none of
 * them could be demonstrated or tested end to end, because registration only
 * ever produces a {@code CUSTOMER} and there was no supported way to make
 * anything else. Three separate pieces of work were left deferred for want of
 * this one account.
 *
 * <h2>Why this is safe to ship, and what would make it unsafe</h2>
 *
 * <p>It does nothing unless {@code northbank.demo.staff.enabled} is explicitly
 * true, and that is set in {@code docker-compose.yml} — the local stack — and
 * nowhere else. A deployment that does not set it gets no account, no log line
 * and no bean.
 *
 * <p>The credentials are as synthetic as the rest of the demo data, and are
 * visible in the Compose file beside the database password that has always been
 * there. That is the existing convention for this repository, which exists to
 * be read and run locally. It is not a pattern to copy into anything real: a
 * deployed system would create staff through an administrative process, with
 * credentials that were never written down in a repository.
 *
 * <p>The account is created once and then left alone. It is never re-created
 * with a fresh password on restart, and an existing account with the same
 * username is not modified — so a password changed locally stays changed.
 */
@Configuration
@ConditionalOnProperty(prefix = "northbank.demo.staff", name = "enabled", havingValue = "true")
@Slf4j
public class DemoStaffBootstrap {

    @Bean
    ApplicationRunner createDemoStaffAccount(UserRepository users,
                                             PasswordEncoder passwordEncoder,
                                             DemoStaffProperties properties) {
        return args -> {
            String username = properties.getUsername();
            if (users.findByUsername(username).isPresent()) {
                log.info("Demo staff account '{}' already exists; leaving it alone", username);
                return;
            }

            users.save(User.builder()
                    .username(username)
                    .email(properties.getEmail())
                    .password(passwordEncoder.encode(properties.getPassword()))
                    .firstName("Northbank")
                    .lastName("Reviewer")
                    .role(Role.EMPLOYEE)
                    .enabled(true)
                    // Staff are not customers: no credit application, no KYC of
                    // their own to complete.
                    .kycStatus(KycStatus.APPROVED)
                    .dateOfBirth(LocalDate.of(1985, 1, 1))
                    .build());

            log.warn("Created demo staff account '{}' — local demonstration stack only", username);
        };
    }
}
