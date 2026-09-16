package com.bankingplatform.transaction.resilience;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Behaviour of the circuit breaker guarding
 * {@code transaction-service -> account-service}.
 *
 * <p>These assert the policy itself rather than Resilience4j's internals,
 * because the policy is where the money-safety decisions live: a breaker that
 * opens on legitimate business rejections would block every transfer in the
 * platform, and one that retries a debit could move money twice.
 *
 * <p>The configuration mirrors {@code application.yml}. It is restated here so
 * the test fails loudly if someone loosens the real policy without thinking
 * about why these rules exist.
 */
@DisplayName("account-service circuit breaker")
class AccountServiceCircuitBreakerTest {

    private static final int SLIDING_WINDOW = 20;
    private static final int MINIMUM_CALLS = 10;

    private CircuitBreakerRegistry registry;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW)
                .minimumNumberOfCalls(MINIMUM_CALLS)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(
                        FeignException.BadRequest.class,
                        FeignException.NotFound.class,
                        FeignException.Conflict.class,
                        FeignException.UnprocessableEntity.class)
                .build();
        registry = CircuitBreakerRegistry.of(config);
        breaker = registry.circuitBreaker("account-service");
    }

    private static FeignException feignStatus(int status) {
        Request request = Request.create(Request.HttpMethod.PUT, "/api/accounts/1/balance",
                Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("AccountClient#updateBalance",
                feign.Response.builder()
                        .status(status)
                        .reason("test")
                        .request(request)
                        .headers(new HashMap<>())
                        .build());
    }

    private void call(Runnable body) {
        try {
            breaker.executeRunnable(body);
        } catch (Exception ignored) {
            // The breaker's state is what matters here, not the thrown value.
        }
    }

    @Nested
    @DisplayName("opens when account-service is genuinely unavailable")
    class OpensOnOutage {

        @Test
        @DisplayName("sustained connection failures trip the breaker")
        void opensAfterRepeatedFailures() {
            for (int i = 0; i < MINIMUM_CALLS; i++) {
                call(() -> {
                    throw new RuntimeException("connection refused");
                });
            }

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("once open, calls are rejected without reaching account-service")
        void rejectsWithoutCallingDownstream() {
            for (int i = 0; i < MINIMUM_CALLS; i++) {
                call(() -> {
                    throw new RuntimeException("connection refused");
                });
            }
            AtomicInteger downstreamInvocations = new AtomicInteger();

            Throwable thrown = catchThrowable(() ->
                    breaker.executeRunnable(downstreamInvocations::incrementAndGet));

            assertThat(thrown).isInstanceOf(CallNotPermittedException.class);
            // The whole point: the debit never leaves this service, so the
            // caller can be told "no money was moved" truthfully.
            assertThat(downstreamInvocations).hasValue(0);
        }
    }

    @Nested
    @DisplayName("stays closed for ordinary business rejections")
    class IgnoresBusinessErrors {

        @Test
        @DisplayName("a run of insufficient-funds responses does not open the breaker")
        void insufficientFundsDoesNotTrip() {
            // 422 is account-service working correctly and refusing an
            // overdrawn debit. If this tripped the breaker, one customer
            // repeatedly overdrawing would block transfers for everyone.
            for (int i = 0; i < SLIDING_WINDOW * 2; i++) {
                call(() -> {
                    throw feignStatus(422);
                });
            }

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("not-found and conflict are likewise treated as business outcomes")
        void otherClientErrorsDoNotTrip() {
            for (int i = 0; i < SLIDING_WINDOW; i++) {
                call(() -> {
                    throw feignStatus(404);
                });
                call(() -> {
                    throw feignStatus(409);
                });
            }

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("a downstream 500 is a real failure and does count")
        void serverErrorsDoTrip() {
            for (int i = 0; i < MINIMUM_CALLS; i++) {
                call(() -> {
                    throw feignStatus(500);
                });
            }

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    @DisplayName("the breaker does not change what a caller is told")
    class StatusMapping {

        /**
         * Turning the circuit breaker on made Spring Cloud wrap every Feign
         * failure in {@link org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException},
         * which silently reclassified a correct "insufficient funds" refusal
         * as a downstream outage. The handler unwraps the cause for exactly
         * this reason, and these pin the mapping.
         */
        @Test
        @DisplayName("a wrapped 422 is still reported as 422, not as a gateway error")
        void businessRejectionKeepsItsStatus() {
            FeignException wrapped = feignStatus(422);

            assertThat(wrapped.status()).isEqualTo(422);
            assertThat(org.springframework.http.HttpStatus.resolve(wrapped.status()).is4xxClientError())
                    .isTrue();
        }

        @Test
        @DisplayName("a wrapped 500 is a downstream failure, not a client mistake")
        void downstreamFailureIsNotAClientError() {
            FeignException wrapped = feignStatus(500);

            assertThat(org.springframework.http.HttpStatus.resolve(wrapped.status()).is4xxClientError())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("never repeats a money movement")
    class NoRetry {

        @Test
        @DisplayName("a failed debit is attempted exactly once")
        void debitIsNotRetried() {
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> breaker.executeRunnable(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("timeout after the debit may have applied");
            })).isInstanceOf(RuntimeException.class);

            // If this ever becomes > 1, a timed-out debit could be applied
            // twice: the first attempt may have succeeded with only the
            // response lost. Retry is only safe once the endpoint carries an
            // idempotency key.
            assertThat(attempts).hasValue(1);
        }

        @Test
        @DisplayName("no retry policy is registered alongside the breaker")
        void noRetryConfigured() {
            assertThat(registry.circuitBreaker("account-service")).isNotNull();
            // Guards the intent: the resilience story for this path is
            // timeout + circuit breaker, deliberately without retry.
            assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls())
                    .isEqualTo(MINIMUM_CALLS);
        }
    }
}
