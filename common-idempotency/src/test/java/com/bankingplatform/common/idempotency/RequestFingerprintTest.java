package com.bankingplatform.common.idempotency;

import com.bankingplatform.common.security.CallerIdentity;
import com.bankingplatform.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Request fingerprint")
class RequestFingerprintTest {

    private static final CallerIdentity CALLER = new CallerIdentity(7L, "customer", Role.CUSTOMER);

    record Scheduled(BigDecimal amount, LocalDateTime scheduledAt, LocalDate endDate) {
    }

    @Test
    @DisplayName("fingerprints a request that carries dates")
    void requestsWithDatesCanBeFingerprinted() {
        // A scheduled payment used to fail here with a 500: the mapper did not
        // know java.time at all.
        Scheduled request = new Scheduled(new BigDecimal("50.00"),
                LocalDateTime.of(2026, 10, 1, 9, 0), LocalDate.of(2027, 10, 1));
        assertThat(RequestFingerprint.of("PAYMENT", CALLER, request)).hasSize(64);
    }

    @Test
    @DisplayName("tells two different scheduled times apart, and not two spellings of one amount")
    void datesAreSignificantAmountsAreNormalised() {
        LocalDateTime nine = LocalDateTime.of(2026, 10, 1, 9, 0);
        String a = RequestFingerprint.of("PAYMENT", CALLER, new Scheduled(new BigDecimal("50"), nine, null));
        String b = RequestFingerprint.of("PAYMENT", CALLER, new Scheduled(new BigDecimal("50.00"), nine, null));
        String c = RequestFingerprint.of("PAYMENT", CALLER, new Scheduled(new BigDecimal("50.00"), nine.plusDays(1), null));
        assertThat(a).isEqualTo(b);
        assertThat(c).isNotEqualTo(a);
    }
}
