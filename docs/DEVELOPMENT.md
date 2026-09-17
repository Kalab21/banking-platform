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

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","email":"demo@example.com","password":"Password123!","firstName":"Demo","lastName":"User"}'
```

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
| `POST` | `/api/transactions/deposit`, `/withdraw`, `/transfer` | Money movement |
| `POST` | `/api/payments`, `/api/payments/beneficiaries` | Payments and beneficiaries |
| `POST` | `/api/loans`, `/api/credit-cards` | Lending and cards |
| `GET` | `/api/statistics` | Aggregated read models |
| `GET` | `/api/notifications` | Paginated user alerts |
| `GET` | `/api/fraud` | Fraud alerts (employee/admin) |
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
3. Optimistic locking (`@Version`) on `Account` and idempotency keys on
   money-movement endpoints.
4. A pending-KYC-documents endpoint so staff review is a queue rather than a
   per-customer lookup.
5. Extend Resilience4j beyond the `transaction-service` → `account-service` hop,
   and add bulkheads.
6. JSON log output, a log aggregator, alerting rules and durable trace storage.
7. Run the live Playwright and PowerShell suites against a Compose stack in CI,
   and publish images to a registry.
