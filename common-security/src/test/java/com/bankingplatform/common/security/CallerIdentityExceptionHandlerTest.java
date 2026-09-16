package com.bankingplatform.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 401/403 mapping, and the neutralising of the request path before it is
 * logged or echoed.
 */
@DisplayName("Caller identity exception handler")
class CallerIdentityExceptionHandlerTest {

    private final CallerIdentityExceptionHandler handler = new CallerIdentityExceptionHandler();

    @Test
    @DisplayName("a denied request is 403")
    void deniedIsForbidden() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/2");

        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccessDeniedException("Not permitted"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "Not permitted");
    }

    @Test
    @DisplayName("a request with no identity is 401, not 403")
    void missingIdentityIsUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/2");

        ResponseEntity<Map<String, Object>> response =
                handler.handleMissingIdentity(new MissingCallerIdentityException("no id"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The reason is not echoed: it describes an internal check, not
        // something the caller can act on.
        assertThat(response.getBody()).containsEntry("message", "Authentication required");
    }

    @Test
    @DisplayName("control characters in the path cannot forge a log line")
    void pathIsNeutralised() {
        // A URI carrying CR/LF would otherwise let a caller append a fabricated
        // entry to the log.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/1");
        request.setRequestURI("/api/accounts/1\r\nWARN  forged log entry");

        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccessDeniedException("Not permitted"), request);

        String path = String.valueOf(response.getBody().get("path"));
        assertThat(path).doesNotContain("\r").doesNotContain("\n");
        assertThat(path).startsWith("/api/accounts/1");
    }

    @Test
    @DisplayName("Unicode line separators are neutralised too, not just ASCII control characters")
    void unicodeSeparatorsNeutralised() {
        // U+2028 and U+2029 are not \p{Cntrl} but several log viewers and
        // terminals render them as line breaks, so a denylist of control
        // characters alone would still allow a forged line.
        //
        // Built from code points rather than written as unicode escapes: the
        // Java compiler resolves those before lexing, so a literal one here
        // would terminate the string and fail to compile.
        String lineSeparator = String.valueOf((char) 0x2028);
        String paragraphSeparator = String.valueOf((char) 0x2029);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRequestURI("/api/accounts/1" + lineSeparator + "WARN forged"
                + paragraphSeparator + "second");

        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccessDeniedException("Not permitted"), request);

        String path = String.valueOf(response.getBody().get("path"));
        assertThat(path).doesNotContain(lineSeparator).doesNotContain(paragraphSeparator);
        assertThat(path).startsWith("/api/accounts/1");
    }

    @Test
    @DisplayName("ordinary path characters survive, so the log stays useful")
    void ordinaryPathPreserved() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRequestURI("/api/accounts/user/42/active");

        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccessDeniedException("Not permitted"), request);

        assertThat(response.getBody()).containsEntry("path", "/api/accounts/user/42/active");
    }

    @Test
    @DisplayName("an over-long path is truncated rather than logged whole")
    void longPathTruncated() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRequestURI("/api/" + "a".repeat(5000));

        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccessDeniedException("Not permitted"), request);

        assertThat(String.valueOf(response.getBody().get("path"))).hasSizeLessThan(250);
    }
}
