package com.bankingplatform.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers caller-identity resolution and the 401/403 mapping in any service
 * that puts this module on the classpath.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CallerIdentityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CallerIdentityArgumentResolver callerIdentityArgumentResolver() {
        return new CallerIdentityArgumentResolver();
    }

    /**
     * Publishes the caller on the request thread for audit attribution.
     *
     * <p>Separate from the argument resolver on purpose: the resolver refuses
     * a request that needs an identity and has none, and this must not. The
     * internal endpoints carry no identity by design, and a filter that
     * rejected them for the sake of a log field would break them.
     */
    @Bean
    @ConditionalOnMissingBean
    public CallerContextFilter callerContextFilter() {
        return new CallerContextFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public CallerIdentityExceptionHandler callerIdentityExceptionHandler() {
        return new CallerIdentityExceptionHandler();
    }

    /**
     * Nested and class-level guarded: a {@code @Bean} method's return type is
     * resolved before a method-level condition can exclude it, so declaring
     * this directly would break any service without Feign on its classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignIdentityPropagation {

        @Bean
        @ConditionalOnMissingBean
        public CallerIdentityFeignInterceptor callerIdentityFeignInterceptor() {
            return new CallerIdentityFeignInterceptor();
        }
    }

    @Bean
    public WebMvcConfigurer callerIdentityWebMvcConfigurer(CallerIdentityArgumentResolver resolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(resolver);
            }
        };
    }
}
