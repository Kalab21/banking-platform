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

    @ExceptionHandler(MissingCallerIdentityException.class)
    public ResponseEntity<Map<String, Object>> handleMissingIdentity(MissingCallerIdentityException ex,
                                                                     HttpServletRequest request) {
        // Logged at warn: on a correctly deployed stack this means someone
        // reached a service without passing through the gateway.
        log.warn("Rejected request to {} with no usable caller identity", request.getRequestURI());
        return body(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex,
                                                                   HttpServletRequest request) {
        log.warn("Denied request to {}: {}", request.getRequestURI(), ex.getMessage());
        return body(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "path", request.getRequestURI()
        ));
    }
}
