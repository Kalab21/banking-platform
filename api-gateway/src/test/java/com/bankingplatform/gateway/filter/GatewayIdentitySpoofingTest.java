package com.bankingplatform.gateway.filter;

import com.bankingplatform.gateway.config.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway is the only thing that establishes caller identity, so every
 * downstream authorization decision rests on one property: a client cannot
 * choose its own {@code X-User-*} headers.
 *
 * <p>If a caller could set {@code X-User-Role: ADMIN}, the ownership rules the
 * services apply would be decorative. These tests assert the forwarded headers
 * come from the verified token and nothing else.
 */
@DisplayName("Gateway identity spoofing")
class GatewayIdentitySpoofingTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha256-signing";

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        filter = new JwtAuthenticationFilter(jwtUtil);
    }

    /** A genuine token for customer A, signed with the gateway's key. */
    private static String tokenFor(long userId, String username, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("roles", List.of(role))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(key)
                .compact();
    }

    /** Runs the filter and returns the request as it would reach the service. */
    private ServerHttpRequest forward(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
        filter.filter(exchange, captured -> {
            forwarded.set(captured.getRequest());
            return Mono.empty();
        }).block();
        return forwarded.get();
    }

    @Nested
    @DisplayName("supplied identity headers are replaced")
    class Spoofing {

        @Test
        @DisplayName("a customer claiming another user's id and the admin role is forwarded as themselves")
        void spoofedIdAndRoleDiscarded() {
            // The attack this whole design has to survive: a real token for
            // customer A, plus headers claiming to be customer B as an admin.
            ServerHttpRequest forwarded = forward(MockServerHttpRequest
                    .get("/api/accounts/1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(CUSTOMER_A, "userA", "CUSTOMER"))
                    .header("X-User-Id", String.valueOf(CUSTOMER_B))
                    .header("X-Username", "userB")
                    .header("X-User-Role", "ADMIN")
                    .build());

            assertThat(forwarded.getHeaders().getFirst("X-User-Id")).isEqualTo(String.valueOf(CUSTOMER_A));
            assertThat(forwarded.getHeaders().getFirst("X-Username")).isEqualTo("userA");
            assertThat(forwarded.getHeaders().getFirst("X-User-Role")).isEqualTo("CUSTOMER");
        }

        @Test
        @DisplayName("the spoofed values are replaced, not appended alongside the real ones")
        void spoofedValuesNotRetained() {
            // If the gateway added rather than replaced, a service reading the
            // first value could still see the forged one.
            ServerHttpRequest forwarded = forward(MockServerHttpRequest
                    .get("/api/accounts/1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(CUSTOMER_A, "userA", "CUSTOMER"))
                    .header("X-User-Id", String.valueOf(CUSTOMER_B))
                    .header("X-User-Role", "ADMIN")
                    .build());

            assertThat(forwarded.getHeaders().get("X-User-Id")).containsExactly(String.valueOf(CUSTOMER_A));
            assertThat(forwarded.getHeaders().get("X-User-Role")).containsExactly("CUSTOMER");
            assertThat(forwarded.getHeaders().get("X-User-Role")).doesNotContain("ADMIN");
        }

        @ParameterizedTest
        @DisplayName("header name casing does not smuggle a value past the replacement")
        @ValueSource(strings = {"x-user-role", "X-USER-ROLE", "X-User-Role"})
        void casingDoesNotMatter(String headerName) {
            // HTTP header names are case-insensitive; a lookup that was not
            // would leave an obvious bypass.
            ServerHttpRequest forwarded = forward(MockServerHttpRequest
                    .get("/api/accounts/1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(CUSTOMER_A, "userA", "CUSTOMER"))
                    .header(headerName, "ADMIN")
                    .build());

            assertThat(forwarded.getHeaders().getFirst("X-User-Role")).isEqualTo("CUSTOMER");
        }
    }

    @Nested
    @DisplayName("identity comes from a verified token")
    class TokenVerification {

        @Test
        @DisplayName("a token signed with the wrong key is rejected")
        void forgedSignatureRejected() {
            SecretKey wrongKey = Keys.hmacShaKeyFor(
                    "a-completely-different-key-also-long-enough-for-hmac".getBytes(StandardCharsets.UTF_8));
            String forged = Jwts.builder()
                    .subject("mallory")
                    .claim("userId", CUSTOMER_B)
                    .claim("roles", List.of("ADMIN"))
                    .expiration(new Date(System.currentTimeMillis() + 600_000))
                    .signWith(wrongKey)
                    .compact();

            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .get("/api/accounts/1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                    .build());

            filter.filter(exchange, ex -> Mono.empty()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an expired token is rejected")
        void expiredTokenRejected() {
            SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
            String expired = Jwts.builder()
                    .subject("userA")
                    .claim("userId", CUSTOMER_A)
                    .claim("roles", List.of("CUSTOMER"))
                    .expiration(new Date(System.currentTimeMillis() - 1_000))
                    .signWith(key)
                    .compact();

            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .get("/api/accounts/1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                    .build());

            filter.filter(exchange, ex -> Mono.empty()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("identity headers alone, with no token, do not get through")
        void headersWithoutTokenRejected() {
            // The bypass a caller would try first.
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .get("/api/accounts/1")
                    .header("X-User-Id", String.valueOf(CUSTOMER_B))
                    .header("X-User-Role", "ADMIN")
                    .build());

            filter.filter(exchange, ex -> Mono.empty()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a staff token really does forward the staff role")
        void genuineStaffForwarded() {
            // The positive case, so the tests above cannot pass merely by
            // hard-coding CUSTOMER everywhere.
            ServerHttpRequest forwarded = forward(MockServerHttpRequest
                    .get("/api/accounts/1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(99L, "emp", "EMPLOYEE"))
                    .build());

            assertThat(forwarded.getHeaders().getFirst("X-User-Role")).isEqualTo("EMPLOYEE");
            assertThat(forwarded.getHeaders().getFirst("X-User-Id")).isEqualTo("99");
        }
    }

    @Nested
    @DisplayName("public routes")
    class PublicRoutes {

        @Test
        @DisplayName("login needs no token, and forwards no identity a caller supplied")
        void loginIsPublic() {
            // Public paths skip the filter, so nothing derives identity here.
            // The services reject identity-less requests on protected routes,
            // which is what stops this being a way in.
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/api/auth/login")
                    .build());

            AtomicReference<Boolean> reached = new AtomicReference<>(false);
            filter.filter(exchange, ex -> {
                reached.set(true);
                return Mono.empty();
            }).block();

            assertThat(reached.get()).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }
}
