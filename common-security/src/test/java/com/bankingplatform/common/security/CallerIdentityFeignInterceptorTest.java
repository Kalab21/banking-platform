package com.bankingplatform.common.security;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Caller identity across a Feign hop")
class CallerIdentityFeignInterceptorTest {

    private final CallerIdentityFeignInterceptor interceptor = new CallerIdentityFeignInterceptor();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static String header(RequestTemplate template, String name) {
        return template.headers().getOrDefault(name, java.util.List.of()).stream().findFirst().orElse(null);
    }

    @Test
    @DisplayName("an inbound request's identity is forwarded, and a spoofed outbound value is dropped")
    void forwardsRequestIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CallerIdentityHeaders.USER_ID, "10");
        request.addHeader(CallerIdentityHeaders.USER_ROLE, "CUSTOMER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();
        template.header(CallerIdentityHeaders.USER_ID, "99");

        // A request identity wins even while a job identity is also set.
        CallerContext.runAs(new CallerIdentity(20L, "job", Role.CUSTOMER), () -> interceptor.apply(template));

        assertThat(header(template, CallerIdentityHeaders.USER_ID)).isEqualTo("10");
        assertThat(header(template, CallerIdentityHeaders.USER_ROLE)).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("with no request, the identity a job runs as is forwarded")
    void forwardsJobIdentity() {
        RequestTemplate template = new RequestTemplate();

        CallerContext.runAs(new CallerIdentity(20L, "scheduled-payment", Role.CUSTOMER),
                () -> interceptor.apply(template));

        assertThat(header(template, CallerIdentityHeaders.USER_ID)).isEqualTo("20");
        assertThat(header(template, CallerIdentityHeaders.USERNAME)).isEqualTo("scheduled-payment");
        assertThat(header(template, CallerIdentityHeaders.USER_ROLE)).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("with neither, nothing is forwarded")
    void forwardsNothingWithoutIdentity() {
        RequestTemplate template = new RequestTemplate();
        template.header(CallerIdentityHeaders.USER_ID, "99");

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKeys(
                CallerIdentityHeaders.USER_ID, CallerIdentityHeaders.USERNAME, CallerIdentityHeaders.USER_ROLE);
    }
}
