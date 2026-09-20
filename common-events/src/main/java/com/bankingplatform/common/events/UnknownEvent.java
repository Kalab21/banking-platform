package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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
 *
 * <p>The fields this consumer does not know are kept rather than dropped.
 * They cost nothing to carry, and they matter in one place: if such an event
 * later fails for an unrelated reason and is dead-lettered, the record
 * published to the dead letter topic is a re-serialization of <em>this</em>
 * object. Discarding the payload would leave an operator with four envelope
 * fields and no way to tell what the event said.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnknownEvent(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Map<String, Object> payload) implements DomainEvent {

    public UnknownEvent {
        payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    /** Collects every field this type does not declare. */
    @JsonAnySetter
    void put(String name, Object value) {
        payload.put(name, value);
    }

    /** Writes them back out flat, exactly where they came from. */
    @JsonAnyGetter
    public Map<String, Object> payload() {
        return payload;
    }

    @Override
    public String partitionKey() {
        return eventId;
    }
}
