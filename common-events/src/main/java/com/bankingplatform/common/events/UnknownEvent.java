package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * An event whose type this consumer does not know.
 *
 * <p>Deserializing to this instead of throwing is what lets one service start
 * publishing a new event type without breaking every existing consumer of the
 * topic. Without it, the first record of a new type would fail deserialization
 * in every consumer group that had not been redeployed, and — because a
 * deserialization failure repeats forever on the same offset — would block the
 * partition rather than merely being ignored.
 *
 * <p>Consumers should treat it as "not for me": no side effect, no error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnknownEvent(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return eventId;
    }
}
