package com.bankingplatform.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What may and may not survive into a log line.
 *
 * <p>The property under test is forgery: a caller must not be able to end the
 * line the service is writing and start one of their own. A fabricated entry
 * that reads plausibly is worse than a missing one, because it will be
 * believed.
 */
@DisplayName("LogSafe")
class LogSafeTest {

    @Nested
    @DisplayName("a value cannot break out of its log line")
    class LineForgery {

        @ParameterizedTest
        @DisplayName("line terminators are replaced")
        @ValueSource(strings = {"\r", "\n", "\r\n", "", "\f", ""})
        void lineTerminatorsReplaced(String terminator) {
            String forged = "key-one" + terminator + "2026-01-01 INFO Transfer approved";

            String safe = LogSafe.value(forged);

            assertThat(safe).doesNotContain(terminator);
            assertThat(safe).startsWith("key-one_");
        }

        @Test
        @DisplayName("U+2028 and U+2029 are replaced, though neither is a control character")
        void unicodeSeparatorsReplaced() {
            // Written as escapes rather than literals: a unicode escape in Java
            // source is resolved before the lexer runs, so a literal   in
            // a string is a compile error, not a character.
            String lineSeparator = String.valueOf((char) 0x2028);
            String paragraphSeparator = String.valueOf((char) 0x2029);

            // A \p{Cntrl} denylist would pass both of these through, and some
            // log viewers render them as breaks.
            assertThat(LogSafe.value("a" + lineSeparator + "b")).isEqualTo("a_b");
            assertThat(LogSafe.value("a" + paragraphSeparator + "b")).isEqualTo("a_b");
        }

        @Test
        @DisplayName("whitespace is replaced, so a value stays one field")
        void whitespaceReplaced() {
            assertThat(LogSafe.value("two words")).isEqualTo("two_words");
            assertThat(LogSafe.value("tab\there")).isEqualTo("tab_here");
        }
    }

    @Nested
    @DisplayName("a legitimate value stays readable")
    class Readability {

        @Test
        @DisplayName("an idempotency key passes through untouched")
        void idempotencyKeyPreserved() {
            // The guard restricts keys to letters, digits and _ . : - so every
            // accepted key survives this intact. A log entry is only useful if
            // it still names the thing it is about.
            assertThat(LogSafe.value("live-3f8a21c0-4d1e-4a77-9f2b-0c5e7d9a1b34"))
                    .isEqualTo("live-3f8a21c0-4d1e-4a77-9f2b-0c5e7d9a1b34");
            assertThat(LogSafe.value("payment-ref:42.7_v2")).isEqualTo("payment-ref:42.7_v2");
        }

        @Test
        @DisplayName("a request path passes through untouched")
        void requestPathPreserved() {
            assertThat(LogSafe.value("/api/transactions/account/42?page=0"))
                    .isEqualTo("/api/transactions/account/42_page=0");
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("an over-long value is truncated and marked")
        void overLongTruncated() {
            String safe = LogSafe.value("k".repeat(5000));

            assertThat(safe).hasSize(LogSafe.DEFAULT_MAX_LENGTH + 3).endsWith("...");
        }

        @Test
        @DisplayName("a value at the limit is not marked as truncated")
        void exactLengthNotMarked() {
            String safe = LogSafe.value("k".repeat(LogSafe.DEFAULT_MAX_LENGTH));

            assertThat(safe).hasSize(LogSafe.DEFAULT_MAX_LENGTH).doesNotEndWith("...");
        }

        @Test
        @DisplayName("null prints as a marker rather than as the word null")
        void nullIsMarked() {
            assertThat(LogSafe.value(null)).isEqualTo("(none)");
        }
    }
}
