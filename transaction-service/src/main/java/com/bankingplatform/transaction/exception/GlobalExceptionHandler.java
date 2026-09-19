package com.bankingplatform.transaction.exception;

import com.bankingplatform.common.observability.LogSafe;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
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

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<ErrorResponse> handleTransaction(TransactionException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), req.getRequestURI());
    }

    /**
     * 504 rather than 503. The distinction is deliberate: this response must
     * not promise that nothing happened, because a timed-out debit may well
     * have been applied and only the response lost.
     */
    @ExceptionHandler(AccountCallTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleAccountTimeout(AccountCallTimeoutException ex,
                                                              HttpServletRequest req) {
        log.error("Account service timed out on {}", LogSafe.value(req.getRequestURI()));
        return build(HttpStatus.GATEWAY_TIMEOUT, ex.getMessage(), req.getRequestURI());
    }

    /**
     * The circuit breaker on the account-service hop rejected the call, or the
     * call failed in a way the wrapper obscured.
     *
     * <p>When the circuit is open, 503 with {@code Retry-After}: nothing is
     * wrong with the request, the dependency is unavailable, and the money
     * movement definitively did <em>not</em> happen, because the call never
     * left this service.
     */
    @ExceptionHandler({CallNotPermittedException.class, NoFallbackAvailableException.class})
    public ResponseEntity<ErrorResponse> handleCircuitOpen(Exception ex, HttpServletRequest req) {
        Throwable cause = (ex instanceof NoFallbackAvailableException) ? ex.getCause() : ex;

        // Enabling the circuit breaker made Spring Cloud wrap *every* Feign
        // failure in NoFallbackAvailableException, including the business
        // rejections below. Unwrapping keeps "insufficient funds" a 422 rather
        // than turning a correct refusal into a 502.
        if (cause instanceof FeignException feign) {
            HttpStatus status = HttpStatus.resolve(feign.status());
            if (status != null && status.is4xxClientError()) {
                return build(status, extractFeignMessage(feign), req.getRequestURI());
            }
            log.error("Account service returned {} on {}", feign.status(),
                    LogSafe.value(req.getRequestURI()), feign);
            return build(HttpStatus.BAD_GATEWAY,
                    "The account service did not respond successfully", req.getRequestURI());
        }

        // Anything other than a rejected call is a genuine downstream failure
        // surfacing through the wrapper, so it must not be reported as
        // "temporarily unavailable".
        if (!(cause instanceof CallNotPermittedException)) {
            log.error("Account service call failed on {}: {}", LogSafe.value(req.getRequestURI()),
                    LogSafe.value(ex.getMessage()), ex);
            return build(HttpStatus.BAD_GATEWAY,
                    "The account service did not respond successfully", req.getRequestURI());
        }
        log.warn("Circuit open for account-service on {}", LogSafe.value(req.getRequestURI()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "20")
                .body(ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .error(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase())
                        .message("The account service is temporarily unavailable. No money was moved.")
                        .path(req.getRequestURI())
                        .build());
    }

    /**
     * The idempotency rules refused the request. Each case carries its own
     * status because the client has to be able to tell them apart: 400 means
     * "you sent this wrong", 409 means "that key is spoken for", 504 means "we
     * do not know what the earlier attempt did".
     *
     * <p>In every case this request executed nothing.
     */
    @ExceptionHandler(IdempotencyException.class)
    public ResponseEntity<ErrorResponse> handleIdempotency(IdempotencyException ex, HttpServletRequest req) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            response = response.header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        }
        return response.body(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(ex.getStatus().value())
                .error(ex.getStatus().getReasonPhrase())
                .message(ex.getMessage())
                .path(req.getRequestURI())
                .build());
    }

    /**
     * A transfer left half-applied: the source was debited and the destination
     * was not credited.
     *
     * <p>500 rather than the credit leg's own status. A 422 here would tell the
     * caller their request was rejected, which would be false — money left the
     * source account. The two legs are separate services with separate
     * databases and there is no compensating transaction, so the only accurate
     * answer is that the server is in an inconsistent state.
     */
    @ExceptionHandler(TransferPartiallyAppliedException.class)
    public ResponseEntity<ErrorResponse> handlePartialTransfer(TransferPartiallyAppliedException ex,
                                                               HttpServletRequest req) {
        // The path is caller input, so it is neutralised before it reaches
        // the line. A request that could inject a newline here could forge an
        // entry claiming a transfer settled cleanly.
        log.error("Partially applied transfer on {}", LogSafe.value(req.getRequestURI()), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(FeignException.UnprocessableEntity.class)
    public ResponseEntity<ErrorResponse> handleFeignUnprocessable(FeignException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, extractFeignMessage(ex), req.getRequestURI());
    }

    /**
     * account-service refused the caller, so the caller is refused here too.
     *
     * <p>Without this the denial fell through to the catch-all and was reported
     * as 500, which reads as a broken server rather than a correct refusal and
     * hides an enforced security control behind what looks like a bug.
     */
    @ExceptionHandler(FeignException.Forbidden.class)
    public ResponseEntity<ErrorResponse> handleFeignForbidden(HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Not permitted to access this resource", req.getRequestURI());
    }

    @ExceptionHandler(FeignException.Unauthorized.class)
    public ResponseEntity<ErrorResponse> handleFeignUnauthorized(HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "Authentication required", req.getRequestURI());
    }

    @ExceptionHandler(FeignException.NotFound.class)
    public ResponseEntity<ErrorResponse> handleFeignNotFound(FeignException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, extractFeignMessage(ex), req.getRequestURI());
    }

    @ExceptionHandler(FeignException.Conflict.class)
    public ResponseEntity<ErrorResponse> handleFeignConflict(FeignException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, extractFeignMessage(ex), req.getRequestURI());
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
