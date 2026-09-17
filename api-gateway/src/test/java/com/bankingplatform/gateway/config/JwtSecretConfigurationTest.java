package com.bankingplatform.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signing key must come from the environment and never from the repository.
 *
 * <p>The configuration previously carried
 * {@code ${JWT_SECRET:banking-platform-secret-key-change-in-production}}. Any
 * deployment that did not override it used a signing key published in a public
 * repository, so tokens could be forged for any user and role — which would
 * defeat every authorization rule the services apply.
 */
@DisplayName("JWT secret configuration")
class JwtSecretConfigurationTest {

    private JwtUtil withSecret(String secret) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        return jwtUtil;
    }

    @Nested
    @DisplayName("no signing key is committed")
    class NoCommittedKey {

        @SuppressWarnings("unchecked")
        private String configuredSecret() throws Exception {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
                assertThat(in).as("gateway application.yml").isNotNull();
                Map<String, Object> root = new Yaml().load(in);
                Map<String, Object> jwt = (Map<String, Object>) root.get("jwt");
                return String.valueOf(jwt.get("secret"));
            }
        }

        @Test
        @DisplayName("the configured value is an unresolved placeholder, with no default")
        void noDefaultInConfiguration() throws Exception {
            // "${JWT_SECRET}" and not "${JWT_SECRET:something}". The presence of
            // a colon would mean a usable fallback.
            assertThat(configuredSecret()).isEqualTo("${JWT_SECRET}");
        }

        @Test
        @DisplayName("the old published key appears nowhere in the configuration")
        void oldKeyAbsent() throws Exception {
            assertThat(configuredSecret()).doesNotContain("banking-platform-secret-key");
        }
    }

    @Nested
    @DisplayName("start-up rejects an unusable key")
    class FailFast {

        @ParameterizedTest
        @DisplayName("a missing or blank secret stops the service starting")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void blankSecretRejected(String secret) {
            assertThatThrownBy(() -> withSecret(secret).validateSecret())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        }

        @Test
        @DisplayName("a secret shorter than 256 bits is rejected at start-up, not at first request")
        void shortSecretRejected() {
            // JJWT would reject this too, but only when a token is first
            // verified — by which time the service is live and failing
            // requests rather than failing to boot.
            assertThatThrownBy(() -> withSecret("too-short-for-hmac-sha256").validateSecret())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("256-bit");
        }

        @Test
        @DisplayName("a key of sufficient length is accepted")
        void adequateSecretAccepted() {
            assertThatCode(() -> withSecret("a-test-only-signing-key-long-enough-for-hmac-sha256").validateSecret())
                    .doesNotThrowAnyException();
        }
    }
}
