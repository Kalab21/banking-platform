# Event contracts

What this platform publishes on Kafka, who consumes it, and what each side
relies on.

Events are explicit types in `common-events`, shared by the service that
publishes each one and the services that consume it. Before that they were
`Map<String, Object>` literals built at the call site, with the field names
written out again by hand in each consumer — a contract nothing checked. Much
of what is listed below as working had never worked, and the build was green
throughout.

## The envelope

Every event carries four fields, and then its own.

| Field | Why |
|---|---|
| `eventId` | Identifies this publication. Kafka is at-least-once, so the same event can arrive twice; a consumer that must not act twice de-duplicates on this |
| `eventType` | The discriminator. A real field in the JSON, not only a Jackson type id, so a record is readable without a schema |
| `eventVersion` | Incremented when a change would break a consumer that ignored it. Adding an optional field does not need a new version; removing one or changing units does |
| `occurredAt` | When the business fact happened, not when it was published, so a retry does not restate the time |

The JSON is flat: envelope and payload side by side, the same shape the maps
had.

## Serialization

Jackson type headers are **off**, on both sides.

With them on, Spring writes the producer's fully-qualified class name into a
`__TypeId__` header and the consumer instantiates whatever class that names.
That makes a Java package rename a breaking protocol change, and it only works
if consumers trust a broad set of packages — four services were configured with
`spring.json.trusted.packages: "*"`.

Instead each consumer deserializes to `DomainEvent` and `@JsonTypeInfo` routes
on the `eventType` field. Trusted packages are narrowed to
`com.bankingplatform.common.events`.

An unrecognised `eventType` deserializes to `UnknownEvent` rather than
throwing. This matters more than it looks: a deserialization failure is not a
skipped record, because the container retries the same offset. Without the
fallback, one service publishing a new event type would stop the partition for
every consumer group that had not been redeployed.

## Partition keys

Kafka orders records within a partition, and the key picks the partition, so
the key is the aggregate whose order matters — the account, the application,
the card, the loan.

Two were wrong. Transaction events were keyed by transaction reference and loan
repayments by payment reference; both are unique per event, so one account's
history and one loan's repayments were scattered across every partition and
could be consumed out of order. `remainingBalance` only means anything in
order. Fraud alerts were published with no key at all.

## The matrix

| Topic | Event | Key | Producer | Consumers | What they use |
|---|---|---|---|---|---|
| `account-events` | `ACCOUNT_CREATED` | accountId | account | notification, statistics | `userId`, `accountType` |
| | `BALANCE_UPDATED` | accountId | account | *none* | — |
| | `OVERDRAFT_TRIGGERED` | accountId | account | notification | `userId`, `overdraftAmount` |
| `transaction-events` | `TRANSACTION_CREATED` | accountId | transaction | notification, statistics, fraud | `userId`, `accountId`, `amount`, `transactionRef` |
| | `TRANSFER_COMPLETED` | accountId (source) | transaction | notification, statistics, fraud | same fields as above |
| `application-events` | `APPLICATION_SUBMITTED` | applicationId | application | statistics | count only |
| | `APPLICATION_APPROVED` | applicationId | application | loan, credit-card, notification, statistics | `productType`, `userId`, `applicationId`, `creditScore`, `requestedAmount` |
| | `APPLICATION_REJECTED` | applicationId | application | notification, statistics | `userId`, `productType` |
| `credit-card-events` | `CREDIT_CARD_CREATED` | cardId | credit-card | notification, statistics | `userId`, `last4` |
| | `CREDIT_CARD_TRANSACTION_COMPLETED` | cardId | credit-card | statistics, user | `userId`, `cardId`, `transactionType`, `amount` |
| | `CREDIT_CARD_STATEMENT_GENERATED` | cardId | credit-card | notification | `userId`, `statementDate` |
| `loan-events` | `LOAN_DISBURSED` | loanId | loan | notification, statistics | `userId`, `principal`, `loanId` |
| | `LOAN_REPAYMENT_MADE` | loanId | loan | statistics, user | `userId`, `amount` |
| | `LOAN_PAID_OFF` | loanId | loan | notification, statistics, user | `userId`, `loanId` |
| `payment-events` | `PAYMENT_COMPLETED` | payerAccountId | payment | notification, statistics | `userId`, `payerAccountId`, `amount`, `paymentRef` |
| | `PAYMENT_FAILED` | payerAccountId | payment | notification, statistics, fraud | `userId`, `payerAccountId` |
| `user-events` | `KYC_APPROVED` / `KYC_REJECTED` | userId | user | notification | `userId` |
| | `TWO_FA_ENABLED` / `TWO_FA_DISABLED` | userId | user | notification | `userId` |
| `integration-events` | `EXTERNAL_TRANSFER_INITIATED` | fromAccountId | integration | *none* | — |
| `fraud-alert-events` | `FRAUD_ALERT_CREATED` | accountId | fraud | *none* | — |

## What was broken

Every one of these was live, and none of it failed visibly. A consumer read a
field that was not there, got `null`, took its "nothing to do" branch and
committed the offset.

| Break | Effect |
|---|---|
| `transaction-events` carried no `userId` | The large-transaction notification, the per-user statistic and the fraud evaluation were all dead |
| `TRANSFER_COMPLETED` published `debitRef`, consumers read `transactionRef` | Same three consumers, on transfers |
| `OVERDRAFT_TRIGGERED` published to `account-events` as `overdraftAmount`, consumed from `transaction-events` as `amount` | The overdraft notice had never been sent |
| Producer emitted `CREDIT_CARD_STATEMENT_GENERATED`, consumer matched `STATEMENT_GENERATED` | The statement notice had never been sent |
| Card events carried no `userId` | No card payment had ever raised a credit score |
| Loan repayment carried no `userId` | No on-time repayment had ever raised a credit score |
| Payment events carried no `userId`; the failure carried no `payerAccountId` | No payment receipt or failure notice had ever been sent, and repeated failed payments — the signal the fraud rule exists for — were never counted |
| `APPLICATION_REJECTED` carried no `productType` | Every rejection said "your product application" |
| `publishApplicationSubmitted` existed and was never called | The submitted-applications statistic had always read zero |
| `CREDIT_CARD_CREATED` carried no card identifier, and the notification called `substring(length - 4)` on the empty default | Every issuance notification threw, and the consumer's catch block swallowed it |
| `user-events` had four consumers and no producer | KYC and two-factor notifications had never been sent once |

## Data minimisation

`ACCOUNT_CREATED` used to carry the full bank account number. No consumer read
it. An account number in an event is a copy of it in every consumer's logs and
in the broker's on-disk segments, retained for as long as the topic is.

The rule applied throughout: an event carries an identifier and, where a
message needs to name something to a customer, a masked form. Never a full
account number, a PAN, an SSN, a password, a token or a TOTP secret. A contract
test asserts this against every event in the module, so a new event that adds
such a field fails the build.

Two deliberate omissions:

- `CREDIT_CARD_CREATED` carries `last4`, not the card number. The notification
  names the card; four digits do that.
- `PAYMENT_FAILED` carries no reason. Nothing consumed one, and the obvious
  value to put there is a caught exception's message, which can contain a
  downstream response body. The reason is still on the payment row, where it is
  access-controlled.

## Events with no consumer

Kept, and listed here rather than deleted or given an invented consumer.

- **`BALANCE_UPDATED`** — the natural record of a balance change, cheap to
  publish.
- **`EXTERNAL_TRANSFER_INITIATED`** — a genuine record of an outward
  instruction, and the obvious attachment point for a future notification or
  settlement-tracking consumer.
- **`FRAUD_ALERT_CREATED`** — an alert is a fact worth emitting; case
  management or staff notification is the obvious future subscriber.

## Behaviour that was removed

Consumers existed for events that nothing produces. They were handlers that
could never run, which read as working features.

- **`LOAN_PAYMENT_DUE` and `LOAN_PAYMENT_MISSED`.** No job anywhere looks for
  an instalment coming due or going unpaid. The notification handlers and the
  −20 credit-score penalty are gone; keeping them implied this platform detects
  delinquency. The notification copy is still in `NotificationService` for
  whenever the scheduler is written.
- **The fraud listener on `credit-card-events`.** It read an `accountId` that a
  card transaction does not have. The honest fix is not to add one: its
  `evaluateCreditCardPurchase` path ends in `freezeAccount(accountId)`, which
  would freeze a customer's *deposit* account because of a card purchase, and
  `FraudAlert.accountId` is `NOT NULL`, so an alert cannot be recorded against
  a card at all. Card fraud needs a card subject in the fraud model and a
  decision about what freezing a card means — a change to that model, not to an
  event contract. `evaluateCreditCardPurchase` and its
  `fraud.rules.cc-single-purchase-threshold` setting are removed with it, so a
  rule that cannot run does not read as an active control.

## Nulls on the wire

A typed event makes a field required in Java. It does not make it required in
the JSON. A record written by an older producer during a rolling deploy, or
replayed from a topic that predates this change, deserializes with `null`
where the record declares a value.

That matters because several of these fields back `NOT NULL` columns —
`notifications.user_id`, `fraud_alerts.account_id`,
`fraud_rules_audit.account_id`. Passing one through would throw out of the
listener, and with no dead-letter topic configured yet the container retries
the same offset ten times and blocks the partition before giving up. A null
account id would also collapse every such event onto one Redis velocity key,
mixing unrelated customers into a single fraud counter.

So consumers still check. The check is no longer the normal path — the field
is populated now — but it is the difference between skipping one odd record
and stalling a partition.

## When processing fails

A record that a consumer cannot process is retried, and then kept.

Four deliveries by default, spaced by exponential backoff, and then the record
is published to `<topic>.DLT` and the offset commits so the partition moves on.
The policy lives in `common-kafka` as an auto-configuration rather than in each
service, because six services consume these topics and the answer to "how many
times, how long, and then where" should not be able to differ between them.
`kafka.recovery.*` makes the numbers configurable.

Both halves of that were missing. Listeners used to catch their own exceptions
and return normally, which tells the container the record succeeded. With that
removed, the container's default applied instead: ten immediate attempts and
then commit the offset anyway — a tight loop that cannot outlast the outage it
is retrying, followed by silent loss.

### The deserializer has to be wrapped

Deserialization happens *before* the listener runs, so a payload that cannot be
read never reaches the error handler. Unwrapped, the container retries the same
offset forever: one malformed record stops that partition permanently while the
service still reports itself healthy.

Every consuming service therefore sets
`value-deserializer: ErrorHandlingDeserializer` with the JSON one as
`spring.deserializer.value.delegate.class`. A failure then arrives as a record
the error handler can route. It is not retried — it cannot succeed — so it goes
straight to the dead letter topic.

A start-up check logs an error if a service consumes without the wrapper. It is
a one-line mistake with a disproportionate consequence and nothing a normal
test would catch.

### Headers are transport metadata

A record's payload and its headers are two trust boundaries, and Spring maps
them separately. The payload contract above — type headers off, routing on
`eventType`, trusted packages narrowed — constrains none of the header
handling.

Left at the framework default, an inbound record can carry a
`spring_json_header_types` header naming a Java class and the mapper
constructs it while building the message for the listener, from bytes the
producer chose. `SimpleKafkaHeaderMapper` is installed platform-wide instead:
headers arrive as raw bytes, no type name is honoured, and no object is built
from a producer-supplied header.

Northbank has no use for typed header objects. What travels in a header here
is a correlation id, trace context, event identity and dead-letter metadata —
strings and bytes.

`KafkaHeaderSafetyIT` asserts this against the effective listener
configuration rather than against the mapper in isolation, including that
ordinary events, correlation headers, dead-lettering and malformed-payload
handling all still behave.

### What a dead-lettered record carries

| Header | From |
|---|---|
| `kafka_dlt-original-topic`, `-partition`, `-offset`, `-timestamp` | Spring |
| `kafka_dlt-exception-fqcn`, `-exception-cause-fqcn`, `-exception-message`, `-exception-stacktrace` | Spring |
| `x-dlt-consumer-group` | added here |
| `x-dlt-event-id`, `x-dlt-event-type` | added here |
| `x-dlt-attempts` | added here |

The consumer group matters more than it looks. Five services consume
`transaction-events` and all of them dead-letter to `transaction-events.DLT`,
so without the group a record there says something failed but not who failed to
handle it — and replaying it to everyone would repeat the four side effects
that did succeed.

The event id is read from the payload, and from the raw bytes carried on the
exception when deserialization is what failed. That is precisely the record
that is otherwise hardest to identify.

`x-dlt-attempts` is what actually happened, not what the policy allows. The
recoverer is reached by two routes and they differ: a retryable failure arrives
having exhausted every attempt, while a fatal one — a payload that cannot be
deserialized — is recovered after a single delivery without being retried at
all. Reporting the configured maximum for both would tell an operator that an
unreadable record was retried for several seconds against a dependency when the
listener was never invoked once.

The consumer group is read from the container that failed rather than from the
service-wide property, because `@KafkaListener` can set a group of its own.

**What the payload is.** For a deserialization failure it is the original bytes,
byte for byte. For a listener failure it is a re-serialization of the
deserialized event — which is the same thing for a known type, and for an
unrecognised one is whatever `UnknownEvent` retained. `UnknownEvent` therefore
keeps every field it does not declare, so a dead-lettered event from a newer
producer still carries what it said rather than four envelope fields.

The exception detail and the payload sit side by side on the dead letter topic.
That is safe here because the events carry no account number, card number,
password or token — a contract test in `common-events` asserts it — so there is
nothing in a payload that should not also be in a log.

Dead letter topics are created on demand: the broker has
`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`. A deployment that turned that off would
need them declared.

### What this does not give you

Nothing consumes the dead letter topics. A record there is retained and
inspectable, not automatically replayed; deciding what to do with it is an
operator's job, and replaying safely needs the consumer idempotency that is
still outstanding.

## Surviving redelivery

Kafka is at-least-once, and the retry described above makes redelivery
ordinary rather than exceptional. A consumer can commit its database work and
die before the offset commit reaches the broker; the next poll hands it the
same record again.

Every handler with a durable effect claims the event id before doing anything,
in the same transaction as the work:

```sql
INSERT INTO processed_event (consumer_name, event_id, processed_at)
VALUES (?, ?, ?)
ON CONFLICT (consumer_name, event_id) DO NOTHING
```

A single insert against a composite primary key, not a read-then-write. "Have
I seen this? no, record it" is two statements with a gap between them, and two
concurrent deliveries both read "no" before either writes. Only the database
can decide this once, so the decision is a unique constraint; the losing insert
affects no rows, and under concurrency it blocks until the first transaction
resolves.

The guard uses `Propagation.MANDATORY`. In a transaction of its own the claim
would commit while the work was still uncommitted, and a crash in between would
leave the event marked processed and the work undone — worse than the duplicate
it prevents, because nothing would retry it.

`consumer_name` is the handler, not the service. `notification-service` and
`statistics-service` each run several handlers over the same topics, and each
has to act on an event exactly once.

### Defence in depth for product issuance

Two consumers create a financial product from `APPLICATION_APPROVED`, so a
redelivery there means a second loan or a second card. Those also carry a
partial unique index on `application_id`, so the state cannot exist even if a
duplicate arrives by a route that misses the guard — a manual replay from a
dead letter topic, or a future consumer that forgets it. Partial because
`application_id` is nullable for products created by other routes.

`LoanIssuanceIdempotencyIT` proves both against real PostgreSQL, including
eight threads released together on the same event.

### PostgreSQL, deliberately

`ON CONFLICT DO NOTHING` is not portable SQL, and neither is the `ctid`-bounded
delete the pruner uses. The portable alternative — insert, catch the duplicate
key — marks the caller's transaction rollback-only on what is here the
*ordinary* path, taking the business work down with the duplicate. Every
service on this platform runs PostgreSQL. A service that did not would supply
its own `ProcessedEventGuard`, and `ProcessedEventAutoConfiguration` backs off
for one.

### Retention

The claim table grows with every event a service consumes, and the claim is an
insert against its primary key on the hot path of every consumer, so an
unpruned table makes that index deeper forever. `ProcessedEventRetention`
deletes expired claims nightly, in bounded batches so no single statement holds
a long lock beside live inserts.

**A claim may only be dropped once the broker can no longer deliver the event
it guards.** Prune faster than topic retention and a record still sitting in
Kafka finds no claim on replay and is processed twice — the exact duplicate
this mechanism exists to prevent, reintroduced by the cleanup for it. So the
period is floored at seven days and a shorter setting fails at startup rather
than being quietly honoured.

| Property | Default | Meaning |
|---|---|---|
| `kafka.inbox.retention.enabled` | `true` | whether expired claims are pruned at all |
| `kafka.inbox.retention.period` | `30d` | how long a claim is kept; below 7d is refused |
| `kafka.inbox.retention.batch-size` | `1000` | rows per delete statement |
| `kafka.inbox.retention.cron` | `0 30 3 * * *` | when the prune runs |

The auto-configuration carries its own `@EnableScheduling`: three of the six
consuming services declare none, and a `@Scheduled` method in a context without
it is never called and never complains.

## Publishing what actually happened

`kafkaTemplate.send` is asynchronous. It returns before the broker has
acknowledged anything, so the exception a producer catches is evidence of
neither delivery nor failure — the database transaction could commit while the
event never reached Kafka, and nothing in the platform would know. An account
that exists and was never announced. A transfer whose balances every
downstream view has permanently wrong. An approval that no issuing service
will ever act on.

So the publication is a row, written in the same transaction as the change:

```sql
INSERT INTO outbox_event
    (event_id, event_type, topic, partition_key, payload, created_at)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (event_id) DO NOTHING
```

It commits with the business change or not at all. `OutboxRelay` sends it
afterwards and retries until the broker takes it, so the failure mode moves
from silent loss to visible delay.

`Propagation.MANDATORY`, for the mirror of the reason the processed-event
guard uses it: a row that committed on its own would announce something that
had not happened and might never happen, and a consumer acting on that is
worse than a lost event, because the damage is downstream and nothing
contradicts it.

### Ordering

Rows go out in id order within a partition key, and the Kafka client has been
idempotent by default since 3.0 (`enable.idempotence=true`, `acks=all`), so a
retry cannot reorder in-flight batches on the wire.

Across replicas the danger is two relays holding rows for the same key at
once. `FOR UPDATE SKIP LOCKED` does not prevent that — it stops two relays
taking the same *row*, not two adjacent rows of one key — so the relay claims
a **key**, with a transaction-scoped advisory lock, and a relay that cannot
take a key leaves it to whoever holds it.

That is also why the relay opens its transaction explicitly rather than with
`@Transactional`. The annotation would sit on a method the class calls on
itself, which does not go through the proxy, and the lock would be released
the instant the statement that took it finished — the guarantee would quietly
not exist.

It does mean a database transaction is held open across a call to Kafka, which
is normally how a connection pool starves. Here it is the point, so the
exposure is bounded instead: a few keys per tick, a bounded batch per key, and
a send timeout.

### Failure

A key stops at its first failed send, and the rest of that key waits for the
next tick. Skipping past a failure would deliver events out of order, which
for a balance is worse than delivering them late. Other keys are unaffected,
so one poisoned aggregate does not stall the service. The reason is stored on
the row, and the log moves from warning to error once a row has failed enough
times to count as stalled rather than unlucky.

`KafkaTemplate.send` is not purely asynchronous: it blocks while the client
waits for cluster metadata and throws on the calling thread when
`max.block.ms` expires, which this platform sets to one second. That is caught
and turned into a failed send like any other. Uncaught it would escape the
tick, roll back the rows already marked sent for every other key, and record
no attempt against the row that caused it — which is exactly what it did the
first time the relay ran against the live stack, while every test passed,
because a mocked template only ever returned a failed future.

A send that succeeds but whose row is not marked — the relay dies in between —
is sent again next tick. The outbox is at-least-once; consumers claim the
event id, which is what makes the duplicate harmless. The two halves of this
document depend on each other.

### The payload is serialised at write time

What is stored is what goes on the wire. Serialising in the relay would mean a
change to an event class between the write and the send silently altered an
already-committed publication.

It is serialised with **spring-kafka's** mapper, not the application's. The
`JsonSerializer` these events used to go through builds its own through
`JacksonUtils.enhancedObjectMapper()`, so that mapper — not Boot's — is the
definition of the current wire format, down to how an `Instant` is written.
Boot's is configured by the application's Jackson properties and whatever
modules are on the classpath, and the two agree only by coincidence. Using it
would have re-encoded every event the moment publishing moved to the outbox: a
wire-format change for every consumer, from a refactor meant to change only
where the event is written. `OutboxIT` pins the stored bytes to exactly what
the serializer would have sent.

### Retention, and services that have no outbox

Only **sent** rows are pruned — `published_at IS NOT NULL`, never an age on
`created_at`. An old row that has not been sent is the one row that must never
be deleted: it is a publication the database already promised, and its age
means something is wrong, not that it is stale.

| Property | Default |
|---|---|
| `kafka.outbox.enabled` | `true` |
| `kafka.outbox.poll-interval` | `1000` ms |
| `kafka.outbox.keys-per-poll` | `20` |
| `kafka.outbox.batch-size` | `50` |
| `kafka.outbox.send-timeout` | `10s` |
| `kafka.outbox.retention` | `7d` |

Every service carries `common-kafka`, but a consumer has no `outbox_event` and
a producer has no `processed_event`. The scheduled jobs check the table is
present before polling it — otherwise half the platform would log a SQL error
every second, and the failures worth reading would be buried in it.

## What this does not solve

Publishing is reliable for `account-service`, `transaction-service` and
`application-service` — see "Publishing what actually happened" below. The
remaining producers (`payment-service`, `loan-service`,
`credit-card-service`, `integration-service` and the fraud alert) still call
`kafkaTemplate.send` directly and can still lose an event; migrating them is
separate work.

Consumers are idempotent. Every handler with a durable effect claims the event
id in the same transaction as its work — see "Surviving redelivery" above.

Listeners no longer swallow exceptions, and bounded retry with a dead-letter
topic now stands behind them — see "When processing fails" above.

What the guard does not cover is any effect outside the database transaction it
lives in. A Redis counter, an outbound HTTP call or a published event is not
rolled back with the claim, so a handler with one of those has to make it
idempotent itself — `fraud-detection-service` claims its velocity counter per
event for exactly this reason. Nor does the guard make a *business* fact
unique: it keys on the publication, so two genuine publications of one approval
are two events. The unique index on `application_id` is what stops a second
product existing.
