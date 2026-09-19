package com.bankingplatform.payment.exception;

import com.bankingplatform.common.observability.LogSafe;
import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ErrorResponse> handlePayment(PaymentException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(FeignException.UnprocessableEntity.class)
    public ResponseEntity<ErrorResponse> handleFeignUnprocessable(FeignException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, extractFeignMessage(ex), req.getRequestURI());
    }

    @ExceptionHandler(FeignException.NotFound.class)
    public ResponseEntity<ErrorResponse> handleFeignNotFound(FeignException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, extractFeignMessage(ex), req.getRequestURI());
    }

    /**
     * account-service refused the caller, so the caller is refused here too.
     *
     * <p>Ownership of a payment is resolved by asking account-service who owns
     * the payer account, and that lookup carries the caller's identity. When
     * the account belongs to someone else the hop returns 403, and without this
     * mapping the denial fell through to the catch-all and was reported as 500
     * — an enforced control that reads like a broken server.
     */
    @ExceptionHandler(FeignException.Forbidden.class)
    public ResponseEntity<ErrorResponse> handleFeignForbidden(HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Not permitted to access this resource", req.getRequestURI());
    }

    @ExceptionHandler(FeignException.Unauthorized.class)
    public ResponseEntity<ErrorResponse> handleFeignUnauthorized(HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "Authentication required", req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, message, req.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'", req.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Method " + ex.getMethod() + " is not supported for this endpoint", req.getRequestURI());
    }

    /**
     * A path with no handler is 404.
     *
     * <p>Spring Boot 3.2 raises {@code NoResourceFoundException} for an unmatched
     * route, which the catch-all below would otherwise report as 500. A caller who
     * mistypes a path should be told the path is wrong, not handed a server error
     * that reads like a fault worth probing.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoResource(HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "No such endpoint", req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}: {}", LogSafe.value(req.getRequestURI()),
                LogSafe.value(ex.getMessage()), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req.getRequestURI());
    }

    private String extractFeignMessage(FeignException ex) {
        String body = ex.contentUTF8();
        if (body != null && body.contains("\"message\"")) {
            try {
                int start = body.indexOf("\"message\"") + 11;
                int end = body.indexOf("\"", start);
                return body.substring(start, end);
            } catch (Exception ignored) {}
        }
        return ex.getMessage();
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, String path) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(path)
                .build());
    }
}
