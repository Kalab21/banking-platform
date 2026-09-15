# Banking Platform

A distributed, event-driven **retail banking back end** built as 13 Spring Boot microservices — covering accounts, transactions, payments, credit cards, loans, fraud detection, KYC and notifications, fronted by an API gateway and deployable to AWS via Terraform.

> **Portfolio / learning project.** This is a self-built demonstration system, **not** a real bank and not production-certified financial software. It handles no real money, holds no real customer data, and has not undergone regulatory, audit or penetration review. It exists to demonstrate backend architecture, distributed-systems design and Spring Boot engineering practice.

---

## Overview

Retail banking back ends are rarely one application. They are many bounded domains — identity, ledgers, cards, lending, risk — that must stay consistent, auditable and available while evolving independently.

This project models that problem end to end:

- **Independent services per business domain**, each owning its own PostgreSQL database (database-per-service), so no service reaches into another's tables.
- **Asynchronous, event-driven propagation** over Kafka, so a transaction can update statistics, fire notifications and trigger fraud scoring without the transaction path depending on any of them.
- **A single authenticated entry point** — an API gateway that validates JWTs once and forwards trusted identity headers downstream.
- **An auditable trail** — every state-changing operation writes an `audit_log` row alongside its domain write, inside the same transaction.

**By the numbers:** 13 services, 302 Java source files, 16 REST controllers, ~99 endpoints, 8 Kafka topics, 26 Flyway migrations.

---

## Key Features

Only features actually implemented in this repository are listed.

### Identity and onboarding — `user-service`

- Registration and login issuing JWTs, with BCrypt-hashed passwords.
- **TOTP two-factor authentication** (RFC 6238) with QR provisioning URI.
- **KYC workflow** — document submission, employee/admin review, automatic `kyc_status` transitions.
- **Credit score tracking** with full history, updated reactively from loan and credit-card Kafka events.
- Role-based accounts: `CUSTOMER`, `EMPLOYEE`, `ADMIN`.

### Accounts — `account-service`

- `CHECKING` / `SAVINGS` / `BUSINESS` account types.
- Balance debit/credit API with **overdraft protection**: overdraft limit, overdraft balance, overdraft fee, and automatic `OVERDRAWN` to `ACTIVE` recovery on repayment.
- `FROZEN` / `CLOSED` account states that reject transactions.

### Money movement — `transaction-service`, `payment-service`

- Deposit, withdrawal and account-to-account transfer, each producing an immutable transaction record with `balanceAfter` and a generated reference.
- Beneficiary management, internal and external payments.
- **Scheduled and recurring payments** driven by a polling job (`@Scheduled(fixedDelay = 60000)`).

### Lending and cards — `loan-service`, `credit-card-service`

- Loan **amortization schedule** generation, disbursement, repayment and early payoff.
- Daily **missed-payment job**; loan events feed credit-score updates.
- Credit card purchases, cash advances, a daily **interest accrual job**, a monthly **statement generation job**, and rewards.

### Risk — `fraud-detection-service`

- Rules engine consuming platform events.
- **Redis-backed velocity counters** over a configurable rolling window (`fraud.rules.velocity-window-seconds`, default 3600s / 5 transactions).
- Raises `FraudAlert` records and can **freeze the offending account** through a Feign call to `account-service`.

### Read models and messaging — `statistics-service`, `notification-service`

- Statistics service consumes six event topics and serves platform, per-user and daily-snapshot aggregates, **cached in Redis** via `@Cacheable`.
- Notification service consumes seven event topics and serves paginated, markable-as-read user alerts.

### External rails — `integration-service`

- **Wire / ACH / SWIFT** external-transfer endpoints and **FX rate / currency conversion**.
- These are deliberately **simulated stubs** — no real banking network is contacted. They model the request/response and persistence shape, not a certified integration.

### Edge — `api-gateway`

- Spring Cloud Gateway with Eureka-backed load-balanced routing (`lb://`) to all 12 downstream services.
- **JWT validation `GlobalFilter`** that rejects unauthenticated requests and injects `X-User-Id` / `X-User-Role`.
- **Redis rate limiting** (replenish 20/s, burst 40) keyed per client IP.

---

## Architecture

```
                         +--------------+
      client ----------->| api-gateway  |  :8080
                         | JWT filter   |
                         | rate limiter |
                         +------+-------+
                                |  lb:// via Eureka (:8761)
        +-----------------------+-----------------------+
        v                       v                       v
  user-service            account-service         transaction-service
  application-service     payment-service         credit-card-service
  loan-service            integration-service
        |                       |                       |
        +--------- publish -----+-------- events -------+
                                |
                         +------v------+
                         |    Kafka    |  8 topics
                         +------+------+
                                | consume
              +-----------------+-----------------+
              v                 v                 v
      statistics-service  notification-service  fraud-detection-service
              |                                   |
              +---------- Redis ------------------+
                   (cache, rate limit, velocity)

  PostgreSQL - one database per service, migrated by Flyway
```

**Service inventory**

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

**Kafka topics:** `user-events`, `account-events`, `application-events`, `transaction-events`, `payment-events`, `credit-card-events`, `loan-events`, `integration-events`.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 17 |
| Framework | Spring Boot 3.3.6 |
| Cloud / distributed | Spring Cloud 2023.0.3 — Gateway, Netflix Eureka, OpenFeign (8 services) |
| Security | Spring Security, JJWT 0.12.6, BCrypt, `dev.samstevens.totp` |
| Persistence | Spring Data JPA / Hibernate, PostgreSQL 16, Flyway (26 migrations) |
| Messaging | Apache Kafka (Confluent `cp-kafka` 7.6.1) |
| Caching / counters | Redis 7 |
| Mapping / boilerplate | MapStruct 1.5.5, Lombok |
| API docs | springdoc-openapi 2.6.0 (12 services) |
| Observability | Spring Boot Actuator (all 13 services) |
| Build | Maven multi-module |
| CI | GitHub Actions — `mvn verify` on JDK 17 plus Docker Compose validation |
| Containers | Docker, Docker Compose |
| Cloud infrastructure | Terraform — AWS ECS Fargate, RDS, MSK, ElastiCache, ALB, WAF, CloudFront, Route 53, ECR, Secrets Manager, VPC |

---

## Security

| Control | Implementation |
|---|---|
| Authentication | JWT bearer tokens issued by `user-service`, signed with HS256 via JJWT |
| Password storage | BCrypt (`BCryptPasswordEncoder`) |
| Two-factor | TOTP (RFC 6238), 6-digit / 30-second window |
| Edge enforcement | Gateway `GlobalFilter` validates the JWT before any route is reached; downstream identity arrives as `X-User-Id` / `X-User-Role` |
| Authorization | Spring Security `@EnableMethodSecurity` with role checks (`CUSTOMER` / `EMPLOYEE` / `ADMIN`) — for example, KYC document review is employee/admin only |
| Session model | Fully stateless (`SessionCreationPolicy.STATELESS`); CSRF disabled, as is appropriate for a token-authenticated API |
| Input validation | Jakarta Bean Validation (`@Valid`) on request DTOs across 12 services |
| Error hygiene | `@RestControllerAdvice` maps domain exceptions to typed responses; the catch-all returns a generic message rather than leaking stack traces |
| Rate limiting | Redis token bucket at the gateway, keyed per IP |
| Abuse detection | Fraud velocity rules with automatic account freeze |
| Secret handling | `JWT_SECRET` injected from the environment — see below |

### Secret handling

No real credentials exist in this repository. The JWT signing key is read from the environment with a clearly marked development fallback:

```yaml
jwt:
  secret: ${JWT_SECRET:banking-platform-secret-key-change-in-production}
```

The PostgreSQL credentials in `application.yml` and `docker-compose.yml` are **throwaway local-only values** for the Compose stack; they grant access to nothing outside your machine. Copy `.env.example` to `.env` and set your own values for any real deployment. On AWS, the Terraform stack provisions the database password with `random_password` and stores it in **AWS Secrets Manager** rather than in source.

---

## Banking / Transaction Flow

**Transfer** (`POST /api/transactions/transfer`):

1. The gateway validates the JWT, injects identity headers and routes to `transaction-service`.
2. Same-account transfers are rejected up front.
3. `transaction-service` calls `account-service` over Feign to **debit** the source. `account-service` checks account status, computes available balance including overdraft head-room, and rejects with `InsufficientFundsException` if short.
4. It then calls `account-service` again to **credit** the destination.
5. A `Transaction` row is persisted with a generated reference and `balanceAfter`, plus an `audit_log` row, in one local `@Transactional` unit.
6. A `transaction-events` message is published, and is consumed independently by statistics, notifications and fraud detection.

**Overdraft** (`account-service`): when a debit exceeds the balance but fits within the overdraft limit, the balance floors at zero, the deficit moves to `overdraftBalance`, status becomes `OVERDRAWN`, an overdraft fee is charged, and an overdraft-triggered event is published. A later credit repays the overdraft first and restores `ACTIVE`.

---

## Reliability

**What is implemented**

- `@Transactional` boundaries on state-changing service operations (14 classes), so the domain write and its `audit_log` row commit or roll back together.
- Centralised `@RestControllerAdvice` exception handling in every service, returning structured `ErrorResponse` payloads with timestamp, status, message and path.
- Flyway versioned migrations with `ddl-auto: validate` — the schema is reviewed SQL, never auto-generated at runtime.
- SLF4J structured logging (`@Slf4j`) on business-significant events such as overdraft triggers and fraud alerts.
- Actuator health/info endpoints on all services, with Docker Compose `healthcheck` gating and `depends_on: service_healthy` ordering.
- Kafka producers pinned to `max.block.ms: 1000` and `request.timeout.ms: 1000`, so a broker outage fails fast instead of blocking request threads for the 60-second default.

**Known limitations — stated rather than hidden**

These are the honest gaps between this project and a production ledger, and they are the areas I would address next:

- **Cross-service transfers are not atomic.** The debit and the credit are two separate Feign calls with no saga, compensating transaction or outbox. A failure after a successful debit leaves funds withdrawn but not credited. A production build needs a transactional outbox plus a compensating-credit saga.
- **Balance updates have no optimistic or pessimistic locking.** `updateBalance` is a read-modify-write with no `@Version` or `SELECT ... FOR UPDATE`, so concurrent debits on the same account can interleave and lose an update.
- **No idempotency keys.** A retried transfer will apply twice.
- **No circuit breakers or retries** on Feign calls (Resilience4j is not on the classpath), so a slow downstream service propagates latency upstream.
- **No automated unit or integration tests.** See [Testing](#testing).

---

## Testing

**Current state, stated plainly:**

| Kind | Status |
|---|---|
| Maven build (`mvn clean package`, 13 modules) | Passing |
| Unit tests | **None** — Surefire reports `No tests to run` |
| Integration tests (Testcontainers / `@SpringBootTest`) | None |
| End-to-end suite (`e2e-tests.ps1`) | Present — a PowerShell suite with pass/fail assertions covering 8 flows against the running stack |
| CI (`.github/workflows/ci.yml`) | Runs `mvn -B clean verify` on JDK 17 and validates both Compose files on every push and pull request |

`e2e-tests.ps1` exercises real HTTP against the gateway: user registration and login, the overdraft lifecycle, credit-card application through approval to purchase, loan application through amortization to repayment, beneficiary plus recurring payment, SWIFT transfer and FX conversion, KYC document submission and review authorization, and Kafka-driven credit-score propagation — including a native TOTP implementation so it can complete 2FA.

```powershell
docker compose up -d      # wait for all services to report healthy
.\e2e-tests.ps1
```

**This is the single largest gap in the project.** A live-stack script is not a substitute for a test pyramid. The next work item is JUnit 5 and Mockito unit tests on the service layer — starting with `AccountServiceImpl` overdraft arithmetic and loan amortization — plus Testcontainers integration tests for the JPA and Kafka layers.

---

## Running Locally

**Prerequisites:** JDK 17+, Maven 3.8+, Docker Desktop.

### Option A — full stack in Docker (recommended)

```bash
git clone <this-repo>
cd banking-platform

cp .env.example .env        # then edit the values
mvn clean package           # build all 13 service jars

docker compose up -d        # Postgres, Kafka, Redis, Eureka and 13 services
docker compose ps           # wait until healthy
```

| Endpoint | URL |
|---|---|
| API gateway | <http://localhost:8080> |
| Eureka dashboard | <http://localhost:8761> |
| Kafka UI | <http://localhost:8095> |

### Option B — infrastructure in Docker, services in your IDE

```bash
docker compose -f docker-compose.infra.yml up -d   # Postgres, Kafka, Redis only
```

Then start `eureka-server` first, then `api-gateway`, then whichever services you are working on. Each service defaults to `localhost` for Postgres, Kafka and Redis, so no extra configuration is needed.

### Smoke test

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","email":"demo@example.com","password":"Password123!","firstName":"Demo","lastName":"User"}'
```

### Tear down

```bash
docker compose down          # add -v to also drop the Postgres volume
```

---

## API Documentation

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

---

## Project Structure

```
banking-platform/
├── pom.xml                      # Maven parent - dependency and version management
├── docker-compose.yml           # Full stack: infrastructure + all 13 services
├── docker-compose.infra.yml     # Infrastructure only, for IDE-based development
├── Dockerfile                   # Shared JRE 17 Alpine image, used per service
├── e2e-tests.ps1                # End-to-end suite against the running stack
├── .env.example                 # Environment template - no real credentials
│
├── eureka-server/               # Service discovery
├── api-gateway/                 # Edge: routing, JWT filter, rate limiting
├── user-service/                # Auth, JWT, 2FA, KYC, credit score
├── application-service/         # Product application workflow
├── account-service/             # Accounts, balances, overdraft
├── transaction-service/         # Deposits, withdrawals, transfers
├── payment-service/             # Beneficiaries, payments, recurring
├── credit-card-service/         # Cards, interest, statements, rewards
├── loan-service/                # Amortization, disbursement, repayment
├── statistics-service/          # Kafka-fed aggregates, Redis cached
├── notification-service/        # Kafka-fed user alerts
├── fraud-detection-service/     # Rules engine, Redis velocity, freeze
├── integration-service/         # Wire/ACH/SWIFT stubs, FX
│
├── docker/postgres/init-db.sql  # Creates one database per service
└── infrastructure/aws/          # Terraform: ECS, RDS, MSK, ElastiCache, ALB, WAF, Route 53
```

Each service follows the same layered package layout:

```
com.bankingplatform.<service>/
├── controller/      # REST endpoints, @Valid request binding
├── service/         # Interface plus impl/, @Transactional business logic
├── repository/      # Spring Data JPA
├── model/           # JPA entities and enums
├── dto/             # Request/response types - entities never cross the wire
├── mapper/          # MapStruct entity <-> DTO
├── exception/       # Domain exceptions plus @RestControllerAdvice
├── kafka/           # producer/ and consumer/
├── client/          # Feign clients to sibling services
└── config/          # Security, JWT and Feign configuration
```

---

## Engineering Decisions

**Database per service, never a shared schema.** Each service owns its PostgreSQL database and reaches others only through REST or events. This costs cross-service joins and gives up distributed ACID — a real trade-off, made deliberately to keep services independently deployable and independently migratable.

**Flyway with `ddl-auto: validate`.** Schema changes are explicit, reviewed, versioned SQL. Hibernate is allowed to verify the schema at boot but never to change it; the failure mode of `ddl-auto: update` in a financial system is unacceptable.

**Events for derived state, synchronous calls for authoritative state.** A transfer must know immediately whether the debit succeeded, so that is a Feign call. Statistics, notifications and fraud scoring are derived and tolerate lag, so they consume Kafka. This keeps the critical path short and stops a notification outage from blocking money movement.

**JWT validated once, at the gateway.** Downstream services trust `X-User-Id` / `X-User-Role` rather than re-parsing the token. This is the standard trade-off: less duplicated crypto, at the cost of requiring the service network to be non-public. `user-service` still runs its own filter because it issues the tokens.

**Fast-failing Kafka producers.** `max.block.ms` is pinned to 1000 ms. Kafka's 60-second default means a broker outage silently blocks request threads until the whole service appears hung; failing in one second turns an infrastructure outage into a visible, contained error.

**Audit log written inside the domain transaction.** Every state change writes an `audit_log` row in the same `@Transactional` unit as the business write, so the audit trail cannot silently diverge from reality.

**Interface plus `impl/` split on the service layer.** Slightly more ceremony than strictly necessary at this size, kept for a concrete reason: it preserves the seam that unit tests and alternative implementations will need.

---

## Roadmap

Ordered by what would most improve the system, not by what is easiest:

1. **Test pyramid** — JUnit 5 and Mockito service-layer tests, Testcontainers for JPA and Kafka, MockMvc slices for controllers.
2. **Transactional outbox and saga** for cross-service transfers, closing the atomicity gap.
3. **Optimistic locking** (`@Version`) on `Account`, plus **idempotency keys** on money-movement endpoints.
4. **Resilience4j** circuit breakers, retries and bulkheads on all Feign clients.
5. **Observability** — Micrometer metrics, distributed tracing and structured JSON logs.
6. **Extend CI** — run the end-to-end suite against a Compose stack and publish images to a registry.

---

*Built as a portfolio project to demonstrate Java, Spring Boot, microservices, event-driven architecture and cloud infrastructure engineering.*
