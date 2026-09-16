package com.bankingplatform.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Establishes the request-correlation id at the edge.
 *
 * <p>This is the only place a new id is minted for browser traffic. Every
 * downstream service inherits the header rather than inventing its own, so one
 * id covers the whole request path and appears on every log line it produced.
 *
 * <p>The header is attacker-controlled, so it is validated before being
 * forwarded or logged. An id containing a newline would let a caller forge log
 * entries; an unbounded one would bloat every log line in the request path.
 * Anything that does not match is replaced rather than rejected — a malformed
 * header is not a reason to fail a banking request.
 *
 * <p>Deliberately duplicated from {@code common-observability}'s
 * {@code RequestId} rather than shared: that module is servlet-based, and
 * pulling spring-boot-starter-web onto the gateway's classpath would clash
 * with WebFlux. The rule it encodes is four lines long.
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(RequestIdGlobalFilter.class);
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        boolean reused = incoming != null && VALID.matcher(incoming).matches();
        String requestId = reused ? incoming : UUID.randomUUID().toString();

        if (incoming != null && !reused) {
            // Logged without the offending value, which is exactly the
            // untrusted string that must not reach a log line.
            log.debug("Discarded malformed {} header; issued {}", HEADER, requestId);
        }

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();

        // Set before the chain runs: a downstream error still returns a
        // response the caller can quote back.
        exchange.getResponse().getHeaders().set(HEADER, requestId);

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        // Ahead of JwtAuthenticationFilter, so an authentication rejection is
        // already correlated with the request that caused it.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
