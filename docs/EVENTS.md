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

## What this does not solve

Publishing is still best-effort. A producer catches the exception from
`kafkaTemplate.send` and logs it, and `send` is asynchronous, so a caught
exception is not a delivery guarantee in the first place: the database
transaction can commit and the event never reach the broker. Making that
reliable needs the domain write and the publication to commit together — a
transactional outbox — which is separate work.

Consumers are not idempotent. `APPLICATION_APPROVED` causes `loan-service` and
`credit-card-service` to create a financial product, so a redelivery would
issue a second loan or a second card. `eventId` is the identity that makes
de-duplication possible; using it is separate work.

Listeners no longer swallow exceptions, and bounded retry with a dead-letter
topic now stands behind them — see "When processing fails" above. What remains
is that a retry re-delivers the record, so a consumer whose side effect is not
idempotent can apply it twice.
