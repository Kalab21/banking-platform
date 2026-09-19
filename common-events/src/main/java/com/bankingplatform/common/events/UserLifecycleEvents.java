package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * The four events about a customer's own status, on {@code user-events}.
 *
 * <p>{@code notification-service} had listened on this topic since the
 * beginning and <b>nothing had ever published to it</b>. Four customer
 * notifications — KYC approved, KYC rejected, two-factor enabled, two-factor
 * disabled — were written, wired and dead.
 *
 * <p>These were implemented rather than deleted because all four operations
 * genuinely happen in {@code user-service}: a staff member reviews a KYC
 * document, and a customer turns a second factor on or off. The consumer was
 * not speculative, it was waiting for a producer that had never been written.
 *
 * <p>They carry the user id and nothing else. A second-factor event must
 * never carry the secret or a code, and a KYC event must never carry the
 * document or the identity number behind it — a notification only needs to
 * know whose status changed.
 */
public final class UserLifecycleEvents {

    private UserLifecycleEvents() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KycApproved(
            String eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            Long userId) implements DomainEvent {

        public static final int VERSION = 1;

        public static KycApproved of(Long userId) {
            return new KycApproved(EventMeta.newId(), EventTypes.KYC_APPROVED, VERSION,
                    EventMeta.now(), userId);
        }

        @Override
        public String partitionKey() {
            return String.valueOf(userId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KycRejected(
            String eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            Long userId) implements DomainEvent {

        public static final int VERSION = 1;

        public static KycRejected of(Long userId) {
            return new KycRejected(EventMeta.newId(), EventTypes.KYC_REJECTED, VERSION,
                    EventMeta.now(), userId);
        }

        @Override
        public String partitionKey() {
            return String.valueOf(userId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TwoFactorEnabled(
            String eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            Long userId) implements DomainEvent {

        public static final int VERSION = 1;

        public static TwoFactorEnabled of(Long userId) {
            return new TwoFactorEnabled(EventMeta.newId(), EventTypes.TWO_FA_ENABLED, VERSION,
                    EventMeta.now(), userId);
        }

        @Override
        public String partitionKey() {
            return String.valueOf(userId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TwoFactorDisabled(
            String eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            Long userId) implements DomainEvent {

        public static final int VERSION = 1;

        public static TwoFactorDisabled of(Long userId) {
            return new TwoFactorDisabled(EventMeta.newId(), EventTypes.TWO_FA_DISABLED, VERSION,
                    EventMeta.now(), userId);
        }

        @Override
        public String partitionKey() {
            return String.valueOf(userId);
        }
    }
}
