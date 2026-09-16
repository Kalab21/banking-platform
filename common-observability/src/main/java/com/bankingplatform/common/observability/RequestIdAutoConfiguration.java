package com.bankingplatform.common.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires correlation-id handling into any service that puts this module on the
 * classpath, so no service needs its own copy of the plumbing.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    /**
     * The Feign interceptor lives in a nested class rather than as a
     * {@code @ConditionalOnClass} method on the parent.
     *
     * <p>A {@code @Bean} method's return type is resolved when Spring reflects
     * over the configuration class, which happens before a method-level
     * condition can exclude it. With the bean method declared directly here,
     * any service without Feign on its classpath — {@code eureka-server}, for
     * one — died at startup with {@code ClassNotFoundException:
     * feign.RequestInterceptor}. Guarding the enclosing class instead means
     * the type is never loaded when Feign is absent.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignPropagation {

        @Bean
        @ConditionalOnMissingBean
        public RequestIdFeignInterceptor requestIdFeignInterceptor() {
            return new RequestIdFeignInterceptor();
        }
    }
}
