package com.bankingplatform.gateway.filter;

import com.bankingplatform.gateway.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for GHSA-rhhx-6j8h-8cvw.
 *
 * <p>springdoc before 2.9.1 caches a rendered OpenAPI document per {@code Accept-Language}
 * without bound, so an unauthenticated caller could exhaust the heap by asking
 * for {@code /v3/api-docs} in endless locales. The JWT filter listed
 * {@code /v3/api-docs} and {@code /swagger-ui} as public paths. The docs are
 * now off in every service (see {@code GatewayRouteExposureTest}), and a
 * request for them is no longer waved through unauthenticated either.
 */
@DisplayName("API docs are not public")
class ApiDocsExposureTest {

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "test-secret-key-that-is-long-enough-for-hmac-sha256-signing");
        filter = new JwtAuthenticationFilter(jwtUtil);
    }

    @ParameterizedTest(name = "{0} without a token is refused")
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui/index.html",
            "/swagger-ui.html"})
    void docsPathsNeedAToken(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "x-attacker-1")
                .build());
        AtomicBoolean forwarded = new AtomicBoolean(false);

        filter.filter(exchange, passed -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).as("forwarded without authentication").isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
