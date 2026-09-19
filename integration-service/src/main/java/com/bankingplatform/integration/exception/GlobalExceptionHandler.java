package com.bankingplatform.integration.exception;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(ex.getMessage(), 404));
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedCurrency(UnsupportedCurrencyException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorBody(ex.getMessage(), 422));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(ex.getMessage(), 400));
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
    public ResponseEntity<Map<String, Object>> handleNoResource() {
        return error(HttpStatus.NOT_FOUND, "No such endpoint");
    }

    /**
     * account-service refused the ownership lookup, so the caller is refused here.
     *
     * <p>Without this the denial falls through to the catch-all and is reported
     * as 500, which reads as a broken server rather than a correct refusal and
     * hides an enforced security control behind what looks like a bug.
     */
    @ExceptionHandler(FeignException.Forbidden.class)
    public ResponseEntity<Map<String, Object>> handleFeignForbidden() {
        return error(HttpStatus.FORBIDDEN, "Not permitted to access this resource");
    }

    @ExceptionHandler(FeignException.Unauthorized.class)
    public ResponseEntity<Map<String, Object>> handleFeignUnauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /**
     * The named source account does not exist.
     *
     * <p>Reported as 404 rather than 500, so a caller who mistypes an id is
     * told the id is wrong instead of being handed a server error.
     *
     * <p>This does distinguish "no such account" from "not yours", because
     * {@code AccountController.getById} looks the account up before it
     * authorises: a missing id is 404 for everyone, an id owned by someone
     * else is 403. That is an existence oracle over account ids, and it is the
     * same one {@code /api/accounts/{id}} already answers directly, so
     * mirroring the status here adds no exposure that the platform did not
     * already have. Closing it means making the lookup authorise first and
     * answer 404 for both cases, which belongs in account-service rather than
     * in an error handler downstream of it.
     */
    @ExceptionHandler(FeignException.NotFound.class)
    public ResponseEntity<Map<String, Object>> handleFeignNotFound() {
        return error(HttpStatus.NOT_FOUND, "Account not found");
    }

    /**
     * account-service could not be asked.
     *
     * <p>The ownership check is the only reason this service calls out, so a
     * call that cannot be made is a check that cannot be made. It fails closed
     * with 502 — never by allowing the transfer through unauthorised.
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Map<String, Object>> handleFeign(FeignException ex) {
        HttpStatus status = HttpStatus.resolve(ex.status());
        if (status != null && status.is4xxClientError()) {
            return error(status, "The request was refused");
        }
        log.error("Account service returned {} while authorising a transfer", ex.status(), ex);
        return error(HttpStatus.BAD_GATEWAY, "The account service did not respond successfully");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        // Detail stays in the log; the caller gets a generic message so the
        // response cannot leak internal types or messages.
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("Internal server error", 500));
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(errorBody(message, status.value()));
    }

    private Map<String, Object> errorBody(String message, int status) {
        return Map.of("timestamp", LocalDateTime.now().toString(), "status", status, "error", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Method " + ex.getMethod() + " is not supported for this endpoint");
    }
}
