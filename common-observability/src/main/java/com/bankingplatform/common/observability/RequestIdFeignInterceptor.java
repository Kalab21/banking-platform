package com.bankingplatform.common.observability;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Carries the correlation id across a Feign hop.
 *
 * <p>Without this the chain breaks at the first service boundary: the callee
 * would mint a fresh id and the two halves of the same user request would be
 * impossible to join up in the logs.
 *
 * <p>The id is read from the inbound request first and only then from the MDC.
 * That ordering matters. Spring Cloud CircuitBreaker can invoke the Feign call
 * on a thread other than the one that served the request, and the MDC is
 * thread-local, so an MDC-only lookup silently returned null on the wrapped
 * path and every downstream call was stamped with a freshly minted id.
 * {@code RequestContextHolder} is the same mechanism the existing
 * Authorization-header forwarder relies on, and it survives that hop.
 */
public class RequestIdFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String requestId = fromInboundRequest();

        if (requestId == null) {
            requestId = MDC.get(RequestId.MDC_KEY);
        }
        if (!RequestId.isValid(requestId)) {
            // No inbound request to inherit from — a scheduled job, or a Kafka
            // consumer. Still worth correlating the outgoing call.
            requestId = RequestId.generate();
        }

        // Feign merges rather than replaces repeated headers, so an explicit
        // remove keeps a retried or reused template from accumulating ids.
        template.removeHeader(RequestId.HEADER);
        template.header(RequestId.HEADER, requestId);
    }

    /** The id as it arrived at this service, or null outside a web request. */
    private String fromInboundRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request == null ? null : request.getHeader(RequestId.HEADER);
    }
}
