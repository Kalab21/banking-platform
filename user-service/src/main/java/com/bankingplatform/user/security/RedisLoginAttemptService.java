package com.bankingplatform.user.security;

import com.bankingplatform.user.exception.LoginThrottledException;
import com.bankingplatform.user.exception.ThrottleStoreUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Sign-in attempt counting, in Redis.
 *
 * <p>Redis rather than a field on the user row or an in-process cache: the
 * counter is transient, wants a TTL, and has to be shared across every
 * user-service instance. An in-memory map would reset on restart and would be
 * trivially defeated by spreading attempts across replicas — which is the
 * attack this exists to stop.
 *
 * <p>The key is a SHA-256 digest of the submitted username, not the username
 * itself. Sign-in attempts against a bank are worth protecting even in a cache:
 * anyone reading the keyspace of a shared Redis would otherwise see a list of
 * account names under attack. The digest is of what was typed, so an attempt
 * against a username that does not exist is still counted, and nothing here can
 * be used to tell registered names from unregistered ones.
 *
 * <p>The increment and the first expiry are one script, so a crash between them
 * cannot leave a key that never expires — an account locked out permanently by
 * an infrastructure hiccup. Nothing but the digest and a small integer is ever
 * written: no password, no code, no username.
 */
@Service
@Slf4j
public class RedisLoginAttemptService implements LoginAttemptService {

    private static final String KEY_PREFIX = "auth:attempts:";

    /**
     * Increment, and set the TTL only when the key is created.
     *
     * <p>Returns the count and the remaining TTL together so a caller never has
     * to issue a second round trip that could observe a different state.
     */
    private static final RedisScript<List> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    /**
     * The count and its remaining TTL, read together.
     *
     * <p>One round trip, so the two values describe the same moment. Reading
     * them separately allows the key to expire in between, which would report
     * the full window as the retry delay for a block that had already lifted.
     */
    private static final RedisScript<List> READ_STATE = new DefaultRedisScript<>("""
            local count = redis.call('GET', KEYS[1])
            if not count then
              return nil
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final LoginThrottleProperties policy;

    public RedisLoginAttemptService(StringRedisTemplate redis, LoginThrottleProperties policy) {
        this.redis = redis;
        this.policy = policy;
    }

    @Override
    public void assertNotThrottled(String username) {
        String key = keyFor(username);

        List<?> state;
        try {
            state = redis.execute(READ_STATE, List.of(key));
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }

        // Read in one script rather than a GET followed by a TTL lookup. The
        // two-call version could see the key expire between them and then
        // report a full window as the retry delay -- telling someone to wait
        // fifteen minutes when the block had already lifted.
        if (state == null || state.isEmpty() || state.get(0) == null) {
            return;
        }

        String raw = String.valueOf(state.get(0));
        Long ttlMillis = state.size() > 1 && state.get(1) != null
                ? Long.valueOf(String.valueOf(state.get(1)))
                : null;

        int attempts;
        try {
            attempts = Integer.parseInt(raw);
        } catch (NumberFormatException malformed) {
            // A value this service did not write. Treat it as no attempts
            // rather than as a permanent lockout.
            log.warn("Discarding a malformed sign-in attempt counter");
            return;
        }

        if (attempts >= policy.getMaxAttempts()) {
            throw new LoginThrottledException(retryAfterSeconds(ttlMillis));
        }
    }

    @Override
    public void recordFailure(String username) {
        try {
            redis.execute(INCREMENT, List.of(keyFor(username)),
                    String.valueOf(policy.getWindow().toMillis()));
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public void clear(String username) {
        try {
            redis.delete(keyFor(username));
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    private String keyFor(String username) {
        return KEY_PREFIX + sha256(username == null ? "" : username);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /** At least a second, so a client is never told to retry immediately. */
    private long retryAfterSeconds(Long ttlMillis) {
        long remaining = ttlMillis == null || ttlMillis < 0
                ? policy.getWindow().toMillis()
                : ttlMillis;
        return Math.max(1, (remaining + 999) / 1000);
    }

    private ThrottleStoreUnavailableException unavailable(RuntimeException failure) {
        // The message names the store, not the caller: no username, no digest.
        log.error("Sign-in attempt store is unavailable: {}", failure.getClass().getSimpleName());
        return new ThrottleStoreUnavailableException("Sign-in attempt store unavailable", failure);
    }
}
