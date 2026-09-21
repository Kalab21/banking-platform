package com.bankingplatform.user.security;

import com.bankingplatform.user.exception.LoginThrottledException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Attempt counting against a real Redis.
 *
 * <p>A mock would answer whatever it was told to, and the two properties that
 * matter here are properties of Redis itself: that the increment and the first
 * expiry happen together, and that concurrent increments all land. The rest —
 * what is written, and under what key — is checked by reading the keyspace
 * back, because "we do not store the username" is the kind of claim that should
 * be verified rather than asserted in a comment.
 */
@DisplayName("Sign-in attempt store — against a real Redis")
class RedisLoginAttemptServiceIT {

    private static final String USERNAME = "ada.lovelace";
    private static final String PASSWORD = "DemoPassword123!";
    private static final String TOTP_CODE = "123456";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private LoginThrottleProperties policy;
    private RedisLoginAttemptService attempts;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void reset() {
        Set<String> keys = redis.keys("auth:attempts:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        policy = new LoginThrottleProperties();
        policy.setMaxAttempts(5);
        policy.setWindow(Duration.ofMinutes(15));
        attempts = new RedisLoginAttemptService(redis, policy);
    }

    private static String digestOf(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Test
    @DisplayName("failures below the limit do not block")
    void belowLimit() {
        for (int i = 0; i < 4; i++) {
            attempts.recordFailure(USERNAME);
        }

        assertThatNoException().isThrownBy(() -> attempts.assertNotThrottled(USERNAME));
    }

    @Test
    @DisplayName("the limit blocks, and says how long is left")
    void atLimit() {
        for (int i = 0; i < 5; i++) {
            attempts.recordFailure(USERNAME);
        }

        assertThatThrownBy(() -> attempts.assertNotThrottled(USERNAME))
                .isInstanceOfSatisfying(LoginThrottledException.class, thrown -> {
                    assertThat(thrown.getRetryAfterSeconds()).isPositive();
                    assertThat(thrown.getRetryAfterSeconds()).isLessThanOrEqualTo(15 * 60);
                });
    }

    @Test
    @DisplayName("clearing lets the account sign in again")
    void clearing() {
        for (int i = 0; i < 5; i++) {
            attempts.recordFailure(USERNAME);
        }
        attempts.clear(USERNAME);

        assertThatNoException().isThrownBy(() -> attempts.assertNotThrottled(USERNAME));
    }

    @Test
    @DisplayName("one account's failures leave another account alone")
    void perAccount() {
        for (int i = 0; i < 5; i++) {
            attempts.recordFailure(USERNAME);
        }

        assertThatThrownBy(() -> attempts.assertNotThrottled(USERNAME))
                .isInstanceOf(LoginThrottledException.class);
        assertThatNoException().isThrownBy(() -> attempts.assertNotThrottled("grace.hopper"));
    }

    @Test
    @DisplayName("the first failure sets a TTL, so a counter can never outlive its window")
    void ttlSetOnFirstFailure() throws Exception {
        attempts.recordFailure(USERNAME);

        String key = "auth:attempts:" + digestOf(USERNAME);
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);

        assertThat(ttl).isNotNull();
        assertThat(ttl).isBetween(1L, 15L * 60);
    }

    @Test
    @DisplayName("concurrent failures all land, and the window stays bounded")
    void concurrentFailures() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    attempts.recordFailure(USERNAME);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        String key = "auth:attempts:" + digestOf(USERNAME);
        assertThat(redis.opsForValue().get(key)).isEqualTo(String.valueOf(threads));
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 15L * 60);
    }

    @Test
    @DisplayName("the key is a digest — the raw username is nowhere in the keyspace")
    void keyIsADigest() throws Exception {
        attempts.recordFailure(USERNAME);

        Set<String> keys = redis.keys("*");
        assertThat(keys).isNotNull().hasSize(1);
        String key = keys.iterator().next();

        assertThat(key).isEqualTo("auth:attempts:" + digestOf(USERNAME));
        assertThat(key).doesNotContain(USERNAME);
    }

    @Test
    @DisplayName("nothing but a count is stored — no password, no code")
    void storesOnlyACount() {
        attempts.recordFailure(USERNAME);

        Set<String> keys = redis.keys("*");
        assertThat(keys).isNotNull();
        for (String key : keys) {
            assertThat(key).doesNotContain(PASSWORD).doesNotContain(TOTP_CODE);
            String value = redis.opsForValue().get(key);
            assertThat(value).isEqualTo("1");
            assertThat(value).doesNotContain(PASSWORD).doesNotContain(TOTP_CODE);
        }
    }
}
