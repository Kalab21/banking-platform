package com.bankingplatform.common.observability;

import feign.RequestTemplate;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The correlation id is the thread that ties a user request together across
 * the gateway, the service that handles it and anything it calls downstream.
 * These tests pin the three properties that make it useful: one is always
 * present, a good one survives, and a bad one never reaches a log line.
 */
@DisplayName("Request id propagation")
class RequestIdPropagationTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /** Runs the filter and captures what the MDC held mid-request. */
    private String runFilter(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(RequestId.MDC_KEY));
        filter.doFilter(request, response, chain);
        return seen.get();
    }

    @Nested
    @DisplayName("an id is always established")
    class AlwaysPresent {

        @Test
        @DisplayName("one is minted when the caller sends none")
        void mintsWhenAbsent() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            String duringRequest = runFilter(new MockHttpServletRequest(), response);

            assertThat(duringRequest).isNotBlank();
            assertThat(RequestId.isValid(duringRequest)).isTrue();
            assertThat(response.getHeader(RequestId.HEADER)).isEqualTo(duringRequest);
        }

        @Test
        @DisplayName("the id is echoed on the response so a caller can quote it")
        void echoesOnResponse() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestId.HEADER, "abcdef1234567890");
            MockHttpServletResponse response = new MockHttpServletResponse();

            runFilter(request, response);

            assertThat(response.getHeader(RequestId.HEADER)).isEqualTo("abcdef1234567890");
        }
    }

    @Nested
    @DisplayName("a valid inbound id is preserved")
    class Preserved {

        @Test
        @DisplayName("an upstream correlation survives the hop")
        void keepsValidIncomingId() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestId.HEADER, "11111111-2222-3333-4444-555555555555");

            String duringRequest = runFilter(request, new MockHttpServletResponse());

            assertThat(duringRequest).isEqualTo("11111111-2222-3333-4444-555555555555");
        }
    }

    @Nested
    @DisplayName("an untrusted id is never taken at face value")
    class Rejected {

        @ParameterizedTest
        @DisplayName("hostile or malformed values are replaced, not propagated")
        @NullSource
        @ValueSource(strings = {
                "",
                "short",                                   // below the minimum length
                "has spaces in it",
                "inject\nFAKE LOG LINE level=ERROR",       // forged log entry
                "inject\r\nSet-Cookie: x=y",               // header splitting
                "../../etc/passwd",
                "<script>alert(1)</script>",
        })
        void replacesInvalidIncomingId(String hostile) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (hostile != null) {
                request.addHeader(RequestId.HEADER, hostile);
            }

            String duringRequest = runFilter(request, new MockHttpServletResponse());

            assertThat(duringRequest).isNotEqualTo(hostile);
            assertThat(RequestId.isValid(duringRequest)).isTrue();
        }

        @Test
        @DisplayName("an over-long id is rejected rather than bloating every log line")
        void rejectsOverlongId() {
            assertThat(RequestId.isValid("a".repeat(65))).isFalse();
            assertThat(RequestId.isValid("a".repeat(64))).isTrue();
        }
    }

    @Nested
    @DisplayName("downstream calls inherit the id")
    class Downstream {

        @Test
        @DisplayName("a Feign call carries the caller's id, not a fresh one")
        void feignCarriesCurrentId() {
            MDC.put(RequestId.MDC_KEY, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            try {
                RequestTemplate template = new RequestTemplate();

                new RequestIdFeignInterceptor().apply(template);

                assertThat(template.headers().get(RequestId.HEADER))
                        .containsExactly("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            } finally {
                MDC.remove(RequestId.MDC_KEY);
            }
        }

        @Test
        @DisplayName("the inbound request wins over the MDC, so a thread hop cannot lose the id")
        void inboundRequestTakesPrecedenceOverMdc() {
            // Spring Cloud CircuitBreaker may run the Feign call on another
            // thread, where the MDC is empty. Reading the inbound request is
            // what keeps the chain intact on that path.
            MockHttpServletRequest inbound = new MockHttpServletRequest();
            inbound.addHeader(RequestId.HEADER, "inbound-1111-2222-3333");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
            MDC.remove(RequestId.MDC_KEY);
            try {
                RequestTemplate template = new RequestTemplate();

                new RequestIdFeignInterceptor().apply(template);

                assertThat(template.headers().get(RequestId.HEADER))
                        .containsExactly("inbound-1111-2222-3333");
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        @DisplayName("an invalid inbound id is replaced rather than forwarded")
        void invalidInboundIdIsReplaced() {
            MockHttpServletRequest inbound = new MockHttpServletRequest();
            inbound.addHeader(RequestId.HEADER, "bad id\nwith newline");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
            MDC.remove(RequestId.MDC_KEY);
            try {
                RequestTemplate template = new RequestTemplate();

                new RequestIdFeignInterceptor().apply(template);

                String sent = template.headers().get(RequestId.HEADER).iterator().next();
                assertThat(sent).isNotEqualTo("bad id\nwith newline");
                assertThat(RequestId.isValid(sent)).isTrue();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        @DisplayName("a call with no inbound request still gets a correlatable id")
        void feignMintsForBackgroundWork() {
            MDC.remove(RequestId.MDC_KEY);
            RequestTemplate template = new RequestTemplate();

            new RequestIdFeignInterceptor().apply(template);

            String sent = template.headers().get(RequestId.HEADER).iterator().next();
            assertThat(RequestId.isValid(sent)).isTrue();
        }

        @Test
        @DisplayName("a reused template does not accumulate ids")
        void doesNotAccumulateHeaders() {
            MDC.put(RequestId.MDC_KEY, "11111111-1111-1111-1111-111111111111");
            try {
                RequestTemplate template = new RequestTemplate();
                RequestIdFeignInterceptor interceptor = new RequestIdFeignInterceptor();

                interceptor.apply(template);
                interceptor.apply(template);

                assertThat(template.headers().get(RequestId.HEADER)).hasSize(1);
            } finally {
                MDC.remove(RequestId.MDC_KEY);
            }
        }
    }

    @Test
    @DisplayName("the MDC is cleared so a pooled thread cannot leak the id")
    void clearsMdcAfterRequest() throws Exception {
        runFilter(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(MDC.get(RequestId.MDC_KEY)).isNull();
    }
}
