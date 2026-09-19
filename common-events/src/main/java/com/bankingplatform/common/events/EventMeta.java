package com.bankingplatform.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Fresh envelope values for an event being published now.
 *
 * <p>A tiny helper rather than a base class: these are records, and a record
 * cannot extend one. It exists so no producer writes
 * {@code UUID.randomUUID().toString()} itself and quietly reuses an id, which
 * would defeat the de-duplication every consumer keys on.
 */
public final class EventMeta {

    private EventMeta() {
    }

    /** A new event id. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * The moment the fact happened.
     *
     * <p>Truncated to milliseconds so the value survives a JSON round trip
     * identically on every platform — Windows and Linux disagree about how
     * many sub-second digits {@code Instant.now()} carries, which makes an
     * equality assertion in a contract test pass on one and fail on the other.
     */
    public static Instant now() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
}
