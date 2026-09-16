package com.bankingplatform.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Publishes the incoming correlation id to the MDC for the life of the request.
 *
 * <p>Ordered first so that anything logged by later filters — including
 * security failures, which are exactly the lines worth correlating — already
 * carries the id.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestId = RequestId.resolve(request.getHeader(RequestId.HEADER));
        MDC.put(RequestId.MDC_KEY, requestId);

        // Echoed so a caller — or a developer with the browser network tab
        // open — can quote the id when reporting a problem.
        response.setHeader(RequestId.HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused. Leaving the id behind would
            // silently stamp it onto an unrelated later request.
            MDC.remove(RequestId.MDC_KEY);
        }
    }
}
