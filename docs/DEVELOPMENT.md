# Development

Setup options beyond the default Docker Compose run, plus API reference and
design notes.

**Prerequisites:** JDK 17+, Maven 3.8+, Docker Desktop.

## Infrastructure in Docker, services in your IDE

```bash
docker compose -f docker-compose.infra.yml up -d   # Postgres, Kafka, Redis only
```

Start `eureka-server` first, then `api-gateway`, then the services you are working
on. Each service defaults to `localhost` for Postgres, Kafka and Redis, so no extra
configuration is needed.

## Frontend against a running backend

```bash
cd frontend
npm install
cp .env.example .env.local        # API_GATEWAY_URL=http://localhost:8080
npm run dev                       # http://localhost:3000
```

The backend runs in Docker while the console reloads locally.

## Seeding a demo customer

```bash
./scripts/seed-demo.sh
```

Drives the public API through the gateway — no direct database writes, no
production configuration — and prints the generated credentials. Seeds two
accounts, twelve transactions, a beneficiary, a loan with its amortization
schedule and first repayment, and two KYC documents awaiting review. Names and
numbers are synthetic, and re-running creates a fresh customer.

`SEED_BACKDATE=1` additionally spreads the seeded transaction timestamps over the
preceding weeks so the dashboard balance chart has a date range. That step writes
to the database directly, because `created_at` is a `@CreationTimestamp` and is
not settable through the API. It is off by default.

## Smoke test

Registration creates the customer — an identity and a profile — and nothing
else. No deposit account is opened by it. A checking or savings account comes
later, from an application (`POST /api/applications` with a
`CHECKING_ACCOUNT` or `SAVINGS_ACCOUNT` type) or directly through
`POST /api/accounts`; the seed script does the latter.

Registration still asks for what opening an account will need, because
collecting it once is the point: a legal name, a date of birth, a US
residential address and an identity number. Every value below is synthetic —
`example.com`, the `555-01xx` range reserved for fiction, and a Social Security
number reserved for demonstration use.

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","email":"demo@example.com","password":"Password123!",
       "firstName":"Demo","lastName":"User","dateOfBirth":"1990-01-15",
       "phone":"2405550148","streetAddress":"123 Example Street",
       "city":"Silver Spring","state":"MD","postalCode":"20910",
       "ssn":"123-45-6789"}'
```

The server checks the Social Security number's format, keeps its last four
digits and discards the rest. No column holds the whole number and no endpoint
returns one.

## Building behind a TLS-inspecting proxy

Products that scan HTTPS re-sign `registry.npmjs.org` with their own root. The
host trusts that root; the build container does not, so the frontend image fails
every fetch with `UNABLE_TO_VERIFY_LEAF_SIGNATURE`.

Pass the interceptor's root certificate in. This adds a trust anchor and does not
disable verification:

```bash
EXTRA_CA_CERT_PEM="$(cat your-proxy-root.crt)" docker compose build frontend
```

The certificate is a property of the machine, so it is a build argument rather
than a committed file. The default build is unchanged.

## Tear down

```bash
docker compose down          # add -v to also drop the Postgres volume
```

## API documentation

Every service exposes springdoc-openapi:

- Swagger UI — `http://localhost:<service-port>/swagger-ui.html`
- OpenAPI JSON — `http://localhost:<service-port>/v3/api-docs`

Selected routes, all reached through the gateway on `:8080`:

| Method | Route | Purpose |
|---|---|---|
| `POST` | `/api/auth/register`, `/api/auth/login` | Obtain a JWT |
| `POST` | `/api/auth/2fa/setup`, `/api/auth/2fa/verify` | TOTP enrolment and verification |
| `GET` `POST` | `/api/accounts` | Open and list accounts |
| `POST` | `/api/transactions/deposit`, `/withdraw`, `/transfer` | Money movement — requires `Idempotency-Key` |
| `POST` | `/api/payments`, `/api/payments/beneficiaries` | Payments and beneficiaries (owner or staff) |
| `POST` | `/api/loans`, `/api/credit-cards` | Lending and cards (owner or staff) |
| `GET` `POST` | `/api/loans/{id}/...`, `/api/credit-cards/{id}/...` | Detail, schedule, repayment, purchase, card payment — owner or staff |
| `GET` | `/api/statistics/users/{id}` | A customer's own read models (owner or staff) |
| `GET` | `/api/statistics/platform`, `/api/statistics/daily` | Platform-wide read models — employee/admin only |
| `GET` | `/api/notifications` | Paginated user alerts (owner or staff) |
| `GET` | `/api/fraud/alerts` | Fraud alerts — list, read and review, all employee/admin |
| `POST` | `/api/integrations/wire`, `/ach`, `/swift` | External rails (simulated) |

## Direct service access

The default stack publishes only the console, the gateway, Eureka, Kafka UI and
the infrastructure containers. The business services listed below are reachable
only on the Compose network, so application traffic has to pass the gateway.

To reach one directly — a debugger, its Swagger UI, an actuator endpoint:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d
```

That override bypasses gateway authentication, so use it only locally.

## Service inventory

| Service | Port | Database | Responsibility |
|---|---|---|---|
| `eureka-server` | 8761 | — | Service discovery |
| `api-gateway` | 8080 | — | Routing, JWT filter, rate limiting |
| `user-service` | 8081 | `user_db` | Auth, JWT, 2FA, KYC, credit score |
| `application-service` | 8082 | `application_db` | Account/loan/card application workflow |
| `account-service` | 8083 | `account_db` | Accounts, balances, overdraft |
| `transaction-service` | 8084 | `transaction_db` | Deposits, withdrawals, transfers |
| `payment-service` | 8085 | `payment_db` | Beneficiaries, payments, recurring |
| `statistics-service` | 8086 | `statistics_db` + Redis | Aggregates, daily snapshots |
| `notification-service` | 8087 | `notification_db` | Event-driven user alerts |
| `fraud-detection-service` | 8088 | `fraud_db` + Redis | Rules engine, velocity, freeze |
| `credit-card-service` | 8089 | `credit_card_db` | Cards, interest, statements, rewards |
| `loan-service` | 8090 | `loan_db` | Amortization, disbursement, repayment |
| `integration-service` | 8091 | `integration_db` | Wire/ACH/SWIFT stubs, FX |

Kafka topics: `user-events`, `account-events`, `application-events`,
`transaction-events`, `payment-events`, `credit-card-events`, `loan-events`,
`integration-events`.

## Design decisions

**Database per service.** Each service owns its PostgreSQL database and reaches
others only through REST or events. This costs cross-service joins and gives up
distributed ACID, in exchange for services that deploy and migrate independently.

**The frontend is a backend-for-frontend, not a browser client.** Every call to the
banking API is made by the Next.js server. The JWT lives in an httpOnly cookie the
browser cannot read, and the gateway needs no CORS policy because no cross-origin
request is made. The cost is that the console requires a Node process rather than
a static bundle.

**Flyway with `ddl-auto: validate`.** Schema changes are explicit, versioned SQL.
Hibernate verifies the schema at boot but never changes it.

**An idempotency key on every money-movement request.** `POST /api/transactions/deposit`,
`/withdraw` and `/transfer` require an `Idempotency-Key` header: an opaque,
client-generated value naming one logical operation. It is deliberately not
derived from the amount, the accounts or the time, because two genuinely
identical transfers a minute apart must both be able to succeed.

`transaction-service` records each key in `idempotency_record` under a unique
constraint, alongside a SHA-256 fingerprint of the caller and the normalised
request. The constraint is the mechanism: two concurrent duplicates both attempt
the insert, the database admits one, and the loser resolves against the winner's
row instead of calling `account-service` a second time.

| Situation | Response |
|---|---|
| Missing or malformed key | `400`, nothing executed |
| Same key, same request, first attempt succeeded | the stored response, with `Idempotent-Replay: true` |
| Same key, different request | `409`, nothing executed |
| Same key, first attempt still running | waits briefly for the result, then `409` with `Retry-After` |
| Same key, first attempt refused with nothing applied | executed again — see below |
| Same key, first attempt's outcome unknown | `504`, and never re-executed |

The last two rows are the interesting ones. A refusal that provably moved no
money — a validation error, insufficient funds, a frozen account, an open
circuit that stopped the call leaving this service — releases the key, because
caching a rejection would lock the client out of an operation it is entitled to
retry. A failure that reached `account-service` and then lost the thread — a
timeout, a 5xx, a transfer whose debit landed and whose credit did not — spends
the key permanently. Whether the balance changed is not knowable from
`transaction-service`, and a retry would be a coin-flip between a no-op and a
second debit. Those records are logged for reconciliation rather than resolved
automatically.

This is also why the circuit breaker still has no retry. Idempotency makes a
*client's* repeat safe; it does not make an automatic in-process retry of a
half-completed downstream mutation safe, and nothing here changed that.

**How the console holds up its end.** The key is only worth anything if the
client reuses it, and at first the console did not: it minted one inside each
server action, on every invocation, so every resubmission was a different
logical operation and the table above never applied. The browser now mints one
opaque id when the customer reaches the review step and sends it with every
attempt at that same payment. A refusal that moved nothing keeps the key, so a
retry is the same operation. Only starting a new payment mints a new one.

The console also reads the outcome column rather than flattening it. 503 is the
only server status it treats as a definite "nothing happened", because that is
the only one the backend promises: the circuit was open and the call never left
`transaction-service`. 504 and a bare 500 are treated as unknown, as is a
transport failure, where the answer may be the thing that was lost. For an
unknown outcome the console claims neither result, offers no control that would
resend, and points the customer at their transaction history.

**A pessimistic row lock on every balance change.** `updateBalance`,
`updateStatus` and `updateOverdraftLimit` load the account through
`findByIdForUpdate`, which issues `SELECT ... FOR UPDATE`. Reading the balance,
deciding whether it is sufficient and writing the new figure all happen with the
row locked, so two concurrent debits are applied one after the other rather than
both against the same starting balance.

Pessimistic rather than optimistic. `@Version` would also prevent the lost
update, but by failing the loser with an exception that then has to be caught,
re-read and replayed — and the replay has to re-run the overdraft rules, because
the answer depends on the balance it now sees. A row lock gives the same
correctness by making the second transaction wait, with no retry loop and no
path where a debit is silently attempted twice. Contention on one account is
low; this is a customer's chequing account, not a global counter.

Locking is per account. Nothing in `account-service` holds two account locks at
once, so there is no lock-ordering deadlock to design around: a transfer takes
its two locks in two separate requests, in two separate transactions.

The lock is held for the duration of the transaction, which includes the Kafka
publish. `max.block.ms` is pinned to 1000 ms, so a broker outage extends the
hold by at most a second rather than indefinitely.

**A transfer is still not atomic across services.** The debit and the credit are
two calls to `account-service`, which owns its own database. `@Transactional` on
the transfer method covers this service's rows and nothing else. If the credit
fails after the debit has been applied, the transfer is reported as
`500 — needs reconciliation` rather than as the credit leg's own error, and the
idempotency record settles as unknown so no retry can debit the source twice.
There is no compensating transaction: a saga or a transactional outbox would be
the fix, and neither is implemented.

**Events for derived state, synchronous calls for authoritative state.** A transfer
must know immediately whether the debit succeeded, so that is a Feign call.
Statistics, notifications and fraud scoring tolerate lag, so they consume Kafka.
This keeps the critical path short and prevents a notification outage from blocking
money movement.

**JWT validated once, at the gateway.** Downstream services consume `X-User-Id` /
`X-User-Role` rather than re-parsing the token. The gateway overwrites those
headers on every routed request, and the services are not reachable from outside
the Compose network, which is what makes consuming them safe. Services still apply
their own ownership and role checks; the headers establish *who* is calling, not
*what* they may do. `user-service` runs its own filter because it issues the
tokens. See [SECURITY.md](SECURITY.md).

**Fast-failing Kafka producers.** `max.block.ms` is pinned to 1000 ms. The
60-second default blocks request threads during a broker outage until the service
appears hung.

**Audit log written inside the domain transaction.** Every state change writes an
`audit_log` row in the same `@Transactional` unit as the business write.

## Roadmap

1. Extend the JUnit 5 / Mockito and Testcontainers pattern to `payment`,
   `notification`, `integration` and `application`, which have no service-layer
   tests.
2. Transactional outbox and saga for cross-service transfers.
3. A pending-KYC-documents endpoint so staff review is a queue rather than a
   per-customer lookup.
4. Extend Resilience4j beyond the `transaction-service` → `account-service` hop,
   and add bulkheads.
5. JSON log output, a log aggregator, alerting rules and durable trace storage.
6. Run the live Playwright and PowerShell suites against a Compose stack in CI,
   and publish images to a registry.
