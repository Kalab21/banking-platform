package com.bankingplatform.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Maps authorization failures to 401/403.
 *
 * <p>Ordered ahead of each service's own advice. Those advices end in a
 * {@code @ExceptionHandler(Exception.class)} catch-all, which would otherwise
 * swallow these and report a denied request as 500 — hiding a security control
 * behind what looks like a bug.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CallerIdentityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CallerIdentityExceptionHandler.class);

    /** Long enough to identify a route, short enough not to flood a log line. */
    private static final int MAX_LOGGED_PATH = 200;

    @ExceptionHandler(MissingCallerIdentityException.class)
    public ResponseEntity<Map<String, Object>> handleMissingIdentity(MissingCallerIdentityException ex,
                                                                     HttpServletRequest request) {
        // Logged at warn: on a correctly deployed stack this means someone
        // reached a service without passing through the gateway.
        log.warn("Rejected request to {}: {}", safePath(request), ex.getMessage());
        return body(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex,
                                                                   HttpServletRequest request) {
        log.warn("Denied request to {}: {}", safePath(request), ex.getMessage());
        return body(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    /**
     * The request path is attacker-controlled, so it is reduced to a known-safe
     * alphabet before it reaches a log line or a response body.
     *
     * <p>A path containing CR or LF would otherwise let a caller append
     * fabricated entries to the log — the same forgery this platform already
     * guards against on the correlation-id header.
     *
     * <p>Built as an allowlist of the characters RFC 3986 permits in a path
     * rather than a denylist of control characters. A denylist of
     * {@code \p{Cntrl}} would still pass through U+2028 and U+2029, which some
     * log viewers and consoles render as line breaks. The result is also
     * bounded, so a very long URL cannot swamp the line.
     */
    private static String safePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return "(unknown)";
        }

        int limit = Math.min(path.length(), MAX_LOGGED_PATH);
        StringBuilder safe = new StringBuilder(limit);
        for (int i = 0; i < limit; i++) {
            char c = path.charAt(i);
            safe.append(isPathSafe(c) ? c : '_');
        }
        if (path.length() > MAX_LOGGED_PATH) {
            safe.append("...");
        }
        return safe.toString();
    }

    /** The unreserved and path characters of RFC 3986; everything else is replaced. */
    private static boolean isPathSafe(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || "/-._~:@!$&'()*+,;=%".indexOf(c) >= 0;
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, HttpServletRequest request) {
        // The response echoes the container-decoded path, which the servlet
        // container has already validated; only the log needed neutralising.
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "path", safePath(request)
        ));
    }
}
