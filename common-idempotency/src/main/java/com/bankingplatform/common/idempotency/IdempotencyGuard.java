package com.bankingplatform.common.idempotency;

import com.bankingplatform.common.security.CallerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Runs a money-movement operation at most once per {@code Idempotency-Key}.
 *
 * <p>The sequence is: claim the key in a committed transaction of its own, then
 * execute, then record the verdict. Claiming first is what makes a concurrent
 * duplicate lose — it finds the claim and never performs the operation.
 *
 * <p>Ordering against authorization matters and is the controller's job: the
 * ownership check runs before this guard is entered, so a refused request
 * neither claims a key nor leaves a cached result. A caller can no more reach
 * another customer's money through an idempotency key than without one.
 *
 * <p>What a failure implies about the balance is the one thing this cannot
 * decide for itself, so it asks an {@link OutcomeClassifier} the owning
 * service supplies. See that type for why the default answer is "unknown".
 *
 * <h2>What a replay returns</h2>
 * <ul>
 *   <li>same key, same request, original succeeded — the stored response,
 *       nothing executed;</li>
 *   <li>same key, same request, original refused with nothing applied —
 *       executed again, because a rejection is not an answer worth caching;</li>
 *   <li>same key, same request, original outcome unknown — 504, and never
 *       re-executed;</li>
 *   <li>same key, different request — 409, nothing executed;</li>
 *   <li>same key, original still running — waits briefly for the result, then
 *       409 with {@code Retry-After}.</li>
 * </ul>
 */
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    public static final String HEADER = "Idempotency-Key";

    /** Set on a response that was served from a stored result rather than executed. */
    public static final String REPLAY_HEADER = "Idempotent-Replay";

    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 255;

    /**
     * Bounded alphabet rather than "anything the client sent". The key is
     * opaque to this service but it is stored, logged and compared, and an
     * unbounded string is an unnecessary surface.
     */
    private static final Pattern PERMITTED_KEY = Pattern.compile("[A-Za-z0-9_.:-]+");

    /**
     * How long a duplicate waits for the original to settle. Long enough that
     * an ordinary double submit resolves to the first request's result, short
     * enough that a stuck operation cannot pin request threads: the pool is
     * finite and exhausting it would take the service down.
     */
    private static final long POLL_INTERVAL_MS = 100;
    private static final int POLL_ATTEMPTS = 25;
    private static final int RETRY_AFTER_SECONDS = 3;

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final OutcomeClassifier classifier;

    public IdempotencyGuard(IdempotencyStore store, ObjectMapper objectMapper,
                            OutcomeClassifier classifier) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.classifier = classifier;
    }

    /**
     * @param rawKey       the {@code Idempotency-Key} header, possibly absent
     * @param operation    the logical operation, stored and fingerprinted so one
     *                     key cannot serve a deposit and a withdrawal
     * @param request      the request body, normalised into the fingerprint
     * @param responseType the type a stored response is read back as
     * @param reference    extracts the transaction reference for the record
     * @param action       the operation itself, invoked at most once per key
     */
    public <T> ResponseEntity<T> execute(String rawKey,
                                         String operation,
                                         CallerIdentity caller,
                                         Object request,
                                         Class<T> responseType,
                                         Function<T, String> reference,
                                         Supplier<T> action) {
        return execute(rawKey, operation, caller, request, responseType, reference,
                HttpStatus.CREATED, action);
    }

    /**
     * The same, for an operation whose success is not a creation.
     *
     * <p>The status is part of what a replay returns, so it has to be the
     * operation's own. Crediting a balance answers 200: nothing was created,
     * and a replay that said 201 would be inventing a resource.
     */
    public <T> ResponseEntity<T> execute(String rawKey,
                                         String operation,
                                         CallerIdentity caller,
                                         Object request,
                                         Class<T> responseType,
                                         Function<T, String> reference,
                                         HttpStatus successStatus,
                                         Supplier<T> action) {

        String key = requireValidKey(rawKey);
        String fingerprint = RequestFingerprint.of(operation, caller, request);

        Optional<IdempotencyOutcome> existing = claim(key, operation, fingerprint);
        if (existing.isPresent()) {
            return replay(existing.get(), key, fingerprint, responseType);
        }

        return executeAndRecord(key, reference, successStatus, action);
    }

    private Optional<IdempotencyOutcome> claim(String key, String operation, String fingerprint) {
        try {
            return store.claim(key, operation, fingerprint);
        } catch (DataIntegrityViolationException raced) {
            // Two requests carrying the same key inserted at the same moment.
            // The database admitted one of them; this is the other, so resolve
            // against whatever the winner wrote.
            log.debug("Idempotency key was claimed concurrently");
            return Optional.of(store.find(key).orElseThrow(() -> new IllegalStateException(
                    "An idempotency key collided on insert but no record exists")));
        }
    }

    /**
     * Runs the operation once and records what it did.
     *
     * <p>Takes no response type: the result is the object the action returned,
     * so there is nothing to read back. {@code responseType} is needed only on
     * the replay path, where a stored body has to be deserialised into
     * something.
     */
    private <T> ResponseEntity<T> executeAndRecord(String key,
                                                   Function<T, String> reference,
                                                   HttpStatus successStatus,
                                                   Supplier<T> action) {
        T result;
        try {
            result = action.get();
        } catch (RuntimeException failure) {
            if (classifier.movedNoMoney(failure)) {
                store.markFailed(key);
            } else {
                store.markUnknown(key);
            }
            throw failure;
        }

        // Only now, with the operation actually successful, is a result
        // recorded. Writing COMPLETED before this point would let a replay
        // report money that never moved.
        store.complete(key, successStatus.value(), serialise(result), reference.apply(result));
        return ResponseEntity.status(successStatus).body(result);
    }

    private <T> ResponseEntity<T> replay(IdempotencyOutcome record, String key,
                                         String fingerprint, Class<T> responseType) {
        if (!record.requestHash().equals(fingerprint)) {
            throw IdempotencyException.differentRequest(HEADER);
        }

        IdempotencyOutcome settled = awaitSettlement(record, key);

        return switch (settled.status()) {
            case COMPLETED -> ResponseEntity
                    .status(HttpStatus.valueOf(settled.responseStatus()))
                    .header(REPLAY_HEADER, "true")
                    .body(deserialise(settled.responseBody(), responseType));
            case UNKNOWN -> throw IdempotencyException.outcomeUnknown();
            // Released for retry between the claim and now, or still running
            // when the wait ran out. Either way this request executed nothing.
            case FAILED, IN_PROGRESS -> throw IdempotencyException.stillRunning(RETRY_AFTER_SECONDS);
        };
    }

    /**
     * Waits for an in-flight duplicate to settle so the caller can be given the
     * original result rather than a conflict.
     */
    private IdempotencyOutcome awaitSettlement(IdempotencyOutcome record, String key) {
        IdempotencyOutcome current = record;
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            if (current.status() != IdempotencyStatus.IN_PROGRESS) {
                return current;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw IdempotencyException.stillRunning(RETRY_AFTER_SECONDS);
            }
            current = store.find(key).orElse(current);
        }
        return current;
    }

    private String requireValidKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw IdempotencyException.missingKey(HEADER);
        }
        String key = rawKey.trim();
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            throw IdempotencyException.malformedKey(
                    HEADER + " must be between " + MIN_KEY_LENGTH + " and " + MAX_KEY_LENGTH
                            + " characters.");
        }
        if (!PERMITTED_KEY.matcher(key).matches()) {
            throw IdempotencyException.malformedKey(
                    HEADER + " may contain only letters, digits, and the characters _ . : -");
        }
        return key;
    }

    private String serialise(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the result of a completed transaction", e);
        }
    }

    private <T> T deserialise(String body, Class<T> responseType) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JsonProcessingException e) {
            // The operation did succeed; only the replay is broken. Saying
            // "unknown outcome" here would be a lie in the opposite direction.
            throw new IllegalStateException("Could not read back a stored transaction result", e);
        }
    }
}
