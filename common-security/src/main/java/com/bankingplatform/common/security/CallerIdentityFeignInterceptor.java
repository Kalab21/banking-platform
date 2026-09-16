package com.bankingplatform.common.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Forwards the caller identity established by the gateway across a Feign hop.
 *
 * <p>Some work legitimately spans services on behalf of a user: approving an
 * application provisions an account for the applicant, for example. Without
 * this the downstream call arrives with no identity and is refused, and the
 * only alternatives would be to exempt the endpoint — reopening the hole this
 * change closes — or to let the callee trust a user id in the request body.
 *
 * <p>Mirrors the existing Authorization-header forwarding rather than
 * introducing a second mechanism. Any client-supplied values are removed before
 * the trusted ones are written, so a spoofed header cannot survive a hop even
 * if one somehow reached a service directly.
 *
 * <p>Requests with no inbound identity — scheduled jobs, Kafka consumers —
 * forward nothing, and the callee applies its own rule for identity-less calls.
 */
public class CallerIdentityFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // Never let a caller-supplied value ride along.
        template.removeHeader(CallerIdentityHeaders.USER_ID);
        template.removeHeader(CallerIdentityHeaders.USERNAME);
        template.removeHeader(CallerIdentityHeaders.USER_ROLE);

        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest request = attrs.getRequest();
        if (request == null) {
            return;
        }

        copy(template, request, CallerIdentityHeaders.USER_ID);
        copy(template, request, CallerIdentityHeaders.USERNAME);
        copy(template, request, CallerIdentityHeaders.USER_ROLE);
    }

    private void copy(RequestTemplate template, HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        if (value != null && !value.isBlank()) {
            template.header(header, value);
        }
    }
}
