package com.bankingplatform.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire contract itself.
 *
 * <p>These assert the bytes, not the Java. A producer and a consumer that both
 * compile against the same record still have to agree on what reaches Kafka:
 * the field names, the discriminator, the version, and what is deliberately
 * absent. Every failure this module exists to prevent was invisible in Java
 * and visible in the JSON.
 */
@DisplayName("Event contracts")
class EventContractTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // The same configuration the services use for Kafka payloads.
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private JsonNode serialize(DomainEvent event) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(event));
    }

    @Nested
    @DisplayName("the envelope")
    class Envelope {

        @Test
        @DisplayName("every event carries an id, a type, a version and a time")
        void envelopePresent() throws Exception {
            JsonNode json = serialize(AccountCreated.of(1L, 2L, "CHECKING"));

            assertThat(json.get("eventId").asText()).isNotBlank();
            assertThat(json.get("eventType").asText()).isEqualTo("ACCOUNT_CREATED");
            assertThat(json.get("eventVersion").asInt()).isEqualTo(1);
            assertThat(json.get("occurredAt").asText()).isNotBlank();
        }

        @Test
        @DisplayName("two publications of the same fact get different ids")
        void idsAreUnique() {
            // Consumer de-duplication keys on this, so a reused id would make
            // a second, legitimate event look like a redelivery and be dropped.
            assertThat(AccountCreated.of(1L, 2L, "CHECKING").eventId())
                    .isNotEqualTo(AccountCreated.of(1L, 2L, "CHECKING").eventId());
        }

        @Test
        @DisplayName("the partition key is not serialised")
        void keyIsNotPayload() throws Exception {
            // It addresses the record; it is not part of it.
            assertThat(serialize(AccountCreated.of(1L, 2L, "CHECKING")).has("partitionKey")).isFalse();
        }

        @Test
        @DisplayName("the time survives a round trip unchanged")
        void timeRoundTrips() throws Exception {
            AccountCreated event = AccountCreated.of(1L, 2L, "CHECKING");
            DomainEvent back = mapper.readValue(mapper.writeValueAsString(event), DomainEvent.class);

            assertThat(back.occurredAt()).isEqualTo(event.occurredAt());
        }
    }

    @Nested
    @DisplayName("routing by event type")
    class Routing {

        @Test
        @DisplayName("each type deserialises back to its own class")
        void typesResolve() throws Exception {
            assertThat(roundTrip(AccountCreated.of(1L, 2L, "CHECKING"))).isInstanceOf(AccountCreated.class);
            assertThat(roundTrip(OverdraftTriggered.of(1L, 2L, new BigDecimal("50.00"))))
                    .isInstanceOf(OverdraftTriggered.class);
            assertThat(roundTrip(TransactionCreated.of(1L, "ref", 3L, 2L, "DEPOSIT",
                    new BigDecimal("10.00"), new BigDecimal("110.00"))))
                    .isInstanceOf(TransactionCreated.class);
            assertThat(roundTrip(ApplicationApproved.of(1L, 2L, "CREDIT_CARD", 3L, 700,
                    new BigDecimal("5000")))).isInstanceOf(ApplicationApproved.class);
        }

        /**
         * A new event type must not break consumers that predate it.
         *
         * <p>A deserialization failure is not a skipped record: the container
         * retries the same offset, so one unrecognised type would stop the
         * partition for every consumer group not yet redeployed.
         */
        @Test
        @DisplayName("an unrecognised event type deserialises instead of throwing")
        void unknownTypeIsTolerated() throws Exception {
            String future = """
                    {"eventId":"abc","eventType":"SOMETHING_ADDED_LATER","eventVersion":1,
                     "occurredAt":"2026-01-01T00:00:00Z","somethingNew":42}
                    """;

            DomainEvent event = mapper.readValue(future, DomainEvent.class);

            assertThat(event).isInstanceOf(UnknownEvent.class);
            assertThat(event.eventType()).isEqualTo("SOMETHING_ADDED_LATER");
        }

        @Test
        @DisplayName("an unrecognised field on a known type is ignored")
        void unknownFieldIsTolerated() throws Exception {
            String withExtra = """
                    {"eventType":"ACCOUNT_CREATED","eventId":"abc","eventVersion":1,
                     "occurredAt":"2026-01-01T00:00:00Z","accountId":1,"userId":2,
                     "accountType":"CHECKING","addedByANewerProducer":"ignored"}
                    """;

            DomainEvent event = mapper.readValue(withExtra, DomainEvent.class);

            assertThat(event).isInstanceOf(AccountCreated.class);
            assertThat(((AccountCreated) event).userId()).isEqualTo(2L);
        }

        private DomainEvent roundTrip(DomainEvent event) throws Exception {
            return mapper.readValue(mapper.writeValueAsString(event), DomainEvent.class);
        }
    }

    @Nested
    @DisplayName("fields consumers depend on")
    class RequiredFields {

        /**
         * All three consumers of this event read {@code userId} first and
         * return when it is null. The producer never sent it, so the
         * large-transaction notification, the per-user statistic and the fraud
         * evaluation had all been dead.
         */
        @Test
        @DisplayName("a transaction event names the owning user")
        void transactionCarriesUser() throws Exception {
            JsonNode json = serialize(TransactionCreated.of(1L, "ref-1", 3L, 42L, "DEPOSIT",
                    new BigDecimal("10.00"), new BigDecimal("110.00")));

            assertThat(json.get("userId").asLong()).isEqualTo(42L);
            assertThat(json.get("accountId").asLong()).isEqualTo(3L);
            assertThat(json.get("transactionRef").asText()).isEqualTo("ref-1");
        }

        @Test
        @DisplayName("a completed transfer names the owning user and uses the same reference field")
        void transferCarriesUser() throws Exception {
            JsonNode json = serialize(TransferCompleted.of("debit-1", "credit-1", 3L, 42L, 4L,
                    new BigDecimal("25.00")));

            // The consumers read transactionRef for both event types; this one
            // used to publish debitRef and so always read null.
            assertThat(json.get("transactionRef").asText()).isEqualTo("debit-1");
            assertThat(json.get("userId").asLong()).isEqualTo(42L);
        }

        @Test
        @DisplayName("a rejected application names the product, like the other two")
        void rejectionCarriesProductType() throws Exception {
            JsonNode json = serialize(ApplicationRejected.of(1L, 2L, "CREDIT_CARD", "Score too low"));

            assertThat(json.get("productType").asText()).isEqualTo("CREDIT_CARD");
        }

        @Test
        @DisplayName("an approval carries what the issuing services need")
        void approvalCarriesIssuanceFields() throws Exception {
            JsonNode json = serialize(ApplicationApproved.of(7L, 2L, "PERSONAL_LOAN", null, 720,
                    new BigDecimal("10000")));

            assertThat(json.get("applicationId").asLong()).isEqualTo(7L);
            assertThat(json.get("userId").asLong()).isEqualTo(2L);
            assertThat(json.get("productType").asText()).isEqualTo("PERSONAL_LOAN");
            assertThat(json.get("creditScore").asInt()).isEqualTo(720);
            assertThat(json.get("requestedAmount").decimalValue())
                    .isEqualByComparingTo(new BigDecimal("10000"));
            // One name, not applicationType and productType side by side.
            assertThat(json.has("applicationType")).isFalse();
        }

        @Test
        @DisplayName("an overdraft event names its amount once")
        void overdraftFieldName() throws Exception {
            JsonNode json = serialize(OverdraftTriggered.of(3L, 42L, new BigDecimal("50.00")));

            // Compared numerically: JSON does not preserve BigDecimal scale,
            // so 50.00 comes back as 50.0. Consumers read the value, not the
            // digits, and asserting the text would be asserting Jackson's
            // formatting rather than the contract.
            assertThat(json.get("overdraftAmount").decimalValue())
                    .isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(json.get("userId").asLong()).isEqualTo(42L);
        }
    }

    @Nested
    @DisplayName("what must never travel")
    class Sensitive {

        /**
         * The old account-created event carried the full account number, and no
         * consumer read it. An account number in an event is a copy of customer
         * data in every consumer's logs and in the broker's segments.
         */
        @Test
        @DisplayName("an account-created event carries no account number")
        void noAccountNumber() throws Exception {
            String json = mapper.writeValueAsString(AccountCreated.of(1L, 2L, "CHECKING"));

            assertThat(json).doesNotContain("accountNumber");
        }

        @Test
        @DisplayName("no event in the contract declares a sensitive field")
        void noSensitiveFieldsAnywhere() throws Exception {
            DomainEvent[] all = {
                    AccountCreated.of(1L, 2L, "CHECKING"),
                    BalanceUpdated.of(1L, 2L, new BigDecimal("10.00"), "DEPOSIT"),
                    OverdraftTriggered.of(1L, 2L, new BigDecimal("10.00")),
                    TransactionCreated.of(1L, "r", 1L, 2L, "DEPOSIT", new BigDecimal("1"), new BigDecimal("2")),
                    TransferCompleted.of("d", "c", 1L, 2L, 3L, new BigDecimal("1")),
                    ApplicationSubmitted.of(1L, 2L, "CREDIT_CARD"),
                    ApplicationApproved.of(1L, 2L, "CREDIT_CARD", 3L, 700, new BigDecimal("1")),
                    ApplicationRejected.of(1L, 2L, "CREDIT_CARD", "reason"),
            };

            for (DomainEvent event : all) {
                String json = mapper.writeValueAsString(event).toLowerCase();
                assertThat(json)
                        .as("%s must not carry customer secrets", event.eventType())
                        .doesNotContain("accountnumber")
                        .doesNotContain("cardnumber")
                        .doesNotContain("\"pan\"")
                        .doesNotContain("ssn")
                        .doesNotContain("password")
                        .doesNotContain("token")
                        .doesNotContain("secret");
            }
        }
    }

    @Nested
    @DisplayName("partition keys")
    class Keys {

        /**
         * Kafka orders within a partition, and the key picks the partition, so
         * the key has to be the aggregate whose order matters.
         */
        @Test
        @DisplayName("account and transaction events are keyed by account")
        void keyedByAccount() {
            assertThat(AccountCreated.of(9L, 2L, "CHECKING").partitionKey()).isEqualTo("9");
            assertThat(OverdraftTriggered.of(9L, 2L, new BigDecimal("1")).partitionKey()).isEqualTo("9");
            // Previously the transaction reference, which is unique per event
            // and scattered one account's history across every partition.
            assertThat(TransactionCreated.of(1L, "ref", 9L, 2L, "DEPOSIT",
                    new BigDecimal("1"), new BigDecimal("2")).partitionKey()).isEqualTo("9");
            assertThat(TransferCompleted.of("d", "c", 9L, 2L, 4L, new BigDecimal("1")).partitionKey())
                    .isEqualTo("9");
        }

        @Test
        @DisplayName("application events are keyed by application")
        void keyedByApplication() {
            assertThat(ApplicationSubmitted.of(7L, 2L, "CREDIT_CARD").partitionKey()).isEqualTo("7");
            assertThat(ApplicationApproved.of(7L, 2L, "CREDIT_CARD", 3L, 700,
                    new BigDecimal("1")).partitionKey()).isEqualTo("7");
            assertThat(ApplicationRejected.of(7L, 2L, "CREDIT_CARD", "r").partitionKey()).isEqualTo("7");
        }
    }
}
