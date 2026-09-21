package com.bankingplatform.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Publishes the caller on the request thread, so audit writing can attribute
 * a change without every service method taking an identity parameter.
 *
 * <p>Reads the same headers the argument resolver does. It does not
 * authenticate or authorise anything: the gateway sets those headers and the
 * controller's own checks decide what the caller may do. This only makes the
 * answer reachable from further down.
 *
 * <p><b>A request with no identity is left empty rather than rejected.</b>
 * The internal endpoints deliberately carry none, and failing here would
 * break them for the sake of a log field. Absence is recorded honestly as
 * {@code SYSTEM} instead of being guessed at.
 *
 * <p>Cleared in a finally, always. Servlet threads are pooled, so an identity
 * left behind is not a leak into the void — it is the next request on that
 * thread being attributed to the previous caller, which is worse than having
 * no attribution at all.
 */
public class CallerContextFilter extends OncePerRequestFilter implements Ordered {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            identityOf(request).ifPresent(CallerContext::set);
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
        }
    }

    private java.util.Optional<CallerIdentity> identityOf(HttpServletRequest request) {
        String rawUserId = request.getHeader(CallerIdentityHeaders.USER_ID);
        java.util.Optional<Role> role = Role.parse(request.getHeader(CallerIdentityHeaders.USER_ROLE));
        if (rawUserId == null || rawUserId.isBlank() || role.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new CallerIdentity(Long.valueOf(rawUserId.trim()),
                    request.getHeader(CallerIdentityHeaders.USERNAME), role.get()));
        } catch (NumberFormatException malformed) {
            // The argument resolver rejects this for endpoints that need an
            // identity. Here it is not this filter's job to refuse a request;
            // an unusable header simply means no attribution.
            return java.util.Optional.empty();
        }
    }

    /**
     * Early, so the context is in place before anything that writes an audit
     * row can run.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
