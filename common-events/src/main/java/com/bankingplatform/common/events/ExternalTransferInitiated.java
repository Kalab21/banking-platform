package com.bankingplatform.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A wire, ACH or SWIFT transfer was accepted for sending.
 *
 * <p><b>No consumer subscribes to {@code integration-events} today.</b> That
 * is recorded rather than fixed: inventing a consumer to make the matrix
 * symmetric would add behaviour nobody asked for. The event is a genuine
 * record of an outward instruction and is the natural place for a future
 * notification or settlement-tracking consumer to attach, so it is kept and
 * listed as unconsumed in {@code docs/EVENTS.md}.
 *
 * <p>The beneficiary, IBAN and routing number are deliberately absent. They
 * identify a third party outside this bank, no consumer needs them, and an
 * event is the wrong place to copy them to.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalTransferInitiated(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Long transferId,
        String transferRef,
        String transferType,
        Long fromAccountId,
        BigDecimal amount,
        String currency,
        String estimatedArrival) implements DomainEvent {

    public static final int VERSION = 1;

    public static ExternalTransferInitiated of(Long transferId, String transferRef, String transferType,
                                               Long fromAccountId, BigDecimal amount, String currency,
                                               String estimatedArrival) {
        return new ExternalTransferInitiated(EventMeta.newId(),
                EventTypes.EXTERNAL_TRANSFER_INITIATED, VERSION, EventMeta.now(),
                transferId, transferRef, transferType, fromAccountId, amount, currency, estimatedArrival);
    }

    @Override
    public String partitionKey() {
        return String.valueOf(fromAccountId);
    }
}
