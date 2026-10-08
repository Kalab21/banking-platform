# Northbank

**Event-Driven Retail Banking Platform**

Northbank is a retail banking platform built from Java 21 / Spring Boot microservices
behind a Spring Cloud Gateway and a Next.js backend-for-frontend, with Kafka for
derived workflows, a PostgreSQL database per service and Redis, designed so that money
movement stays correct under retries, concurrency and partial failure.

[![CI](https://github.com/Kalab21/banking-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/Kalab21/banking-platform/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Kalab21/banking-platform/actions/workflows/codeql.yml/badge.svg)](https://github.com/Kalab21/banking-platform/actions/workflows/codeql.yml)
[![Security Scan](https://github.com/Kalab21/banking-platform/actions/workflows/security-scan.yml/badge.svg)](https://github.com/Kalab21/banking-platform/actions/workflows/security-scan.yml)

## What Northbank demonstrates

- **Microservices with clear ownership**: 11 business services behind an API gateway
  with Eureka discovery, each owning its own PostgreSQL database and Flyway migrations.
- **Money-movement correctness**: idempotency keys on every money-moving request,
  `SELECT … FOR UPDATE` on every balance change, and outcomes reported as unknown,
  then reconciled, when the platform cannot know them.
- **Reliable events**: a transactional outbox in every producer, idempotent consumers,
  bounded retry and dead-letter topics.
- **A full lending lifecycle**: application, versioned underwriting, staff review,
  offer, customer decision, exactly-once provisioning, and loan and card servicing.
- **A defended edge**: the JWT is validated once at the gateway, identity headers are
  overwritten, a BFF keeps the bearer token out of the browser, and every service
  checks ownership against stored records.
- **Verification and delivery**: 1,482 automated tests in CI (JUnit, Testcontainers,
  Vitest, Playwright), live full-stack suites, CodeQL and Trivy, Docker Compose for the
  whole stack, and Terraform for an AWS reference deployment.

## End-to-End Architecture

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/architecture/northbank-end-to-end-dark.svg">
  <img src="docs/architecture/northbank-end-to-end.svg" width="1000" alt="Northbank end-to-end architecture. Customers and staff use a web browser that holds no bearer token and talks only to the Next.js backend-for-frontend, which keeps a server-side session and calls the API gateway over REST. In the AWS model, public API traffic arrives through Route 53, CloudFront with AWS WAF and an ACM certificate, and an Application Load Balancer. The Spring Cloud Gateway validates the JWT, forwards trusted identity headers, rate-limits and discovers services through Eureka. Eleven Spring Boot services are grouped by domain: identity; accounts and money movement, where account-service is the only writer of balances; lending and cards; and risk and insight. REST and OpenFeign carry immediate authoritative operations; Kafka events carry derived, asynchronous workflows. Each service owns its PostgreSQL 16 database (RDS in the AWS model); Redis holds rate limits, counters and cache (ElastiCache); Kafka uses a transactional outbox, idempotent consumers, retry and dead-letter topics (Amazon MSK). A runtime and operations rail shows ECS Fargate, ECR, Secrets Manager, CloudWatch Logs, Prometheus, Grafana and Zipkin, and delivery tooling.">
</picture>

The diagram combines Northbank's application topology with its AWS infrastructure model;
service-level calls, event contracts and infrastructure details are documented separately.
In the Terraform model the gateway, Eureka and the eleven services run as ECS Fargate
tasks behind the edge; the Next.js console is its own Node service and is not part of
that Terraform.

Requests enter through the Next.js backend-for-frontend, which makes every banking API
call server-side. The API gateway is the only routed entry to the service network: it
authenticates the caller and forwards an identity it derived itself. Services own their
data, call each other over REST when they need an answer now, and publish Kafka events
for everything that can lag.

The service-level diagram (every OpenFeign call, every topic, ports and databases) and
data ownership are in **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**; the Terraform is
in [`infrastructure/aws/`](infrastructure/aws/).

## Key Design Decisions

- **REST for authoritative operations, Kafka for derived workflows.** A transfer must
  know immediately whether the debit happened, so `transaction-service` calls
  `account-service` synchronously (OpenFeign, with a Resilience4j circuit breaker on
  that path and no automatic retry). Statistics, notifications, fraud scoring and
  product provisioning tolerate lag and consume events, so an outage there never blocks
  money movement. *Trade-off:* a transfer spans two services with no distributed
  transaction, so a partial outcome is recorded and reconciled rather than hidden.
- **Database per service.** Each service owns its schema and migrates independently,
  and only `account-service` writes a balance. *Trade-off:* no cross-service joins and
  no distributed ACID; read models such as statistics are built from events.
- **A backend-for-frontend instead of a browser API client.** The JWT stays in an
  httpOnly cookie, the gateway needs no CORS policy, and the browser receives narrowed
  views rather than API records. *Trade-off:* the console needs a Node runtime rather
  than a static bundle.

## Product Experience

| Customer dashboard | Staff review workbench |
|---|---|
| ![The customer dashboard: total balance, account cards, balance history and recent activity](docs/screenshots/13-dashboard-desktop.png) | ![A referred application with the applicant, what they stated, the policy's reason code, and Approve or Reject](docs/screenshots/31-staff-review-workbench.png) |
| Balances, accounts and activity read from the account and transaction services. | Staff decide referred applications with the policy's reason code and an append-only history. |

```text
Explore / Apply → Underwrite → Review → Offer → Accept → Provision → Service
```

More product workflows: **[docs/PRODUCT-EXPERIENCE.md](docs/PRODUCT-EXPERIENCE.md)**

## Engineering highlights

- **`SELECT … FOR UPDATE`** on every balance change. Two concurrent debits are applied
  one after the other, and this is proven against a real PostgreSQL.
- **Idempotency keys** on deposits, withdrawals, transfers, payments, loan repayments
  and card payments, stored under a unique constraint with a fingerprint of the caller
  and request. The console mints the key at the review step and reuses it on every
  retry; a concurrent duplicate executes once.
- **A transactional outbox** in every producer, so a committed change is never left
  unannounced. Consumers retry with backoff, send failures to a dead-letter topic, and
  discard duplicates.
- **Append-only decision evidence** for underwriting and review, and **stored offer
  terms** that the customer accepts exactly as made.
- **Provisioning confirmation**: an application reads `PROVISIONED` only after the loan
  or card service confirms a real product ID, and a redelivered event creates nothing
  new.
- **Reconciliation**: a transfer that debited but failed to credit is recorded first,
  then reconciled leg by leg. Deciding who is made whole is left to a person.

## Security & Trust Boundaries

The dashed zones in the diagram mark the trust boundaries: the AWS edge, the
application edge (BFF and gateway), the private service network, and the data layer.

- **Identity is established once, at the gateway.** It validates the JWT and overwrites
  `X-User-Id`, `X-Username` and `X-User-Role` on every routed request, so a client
  cannot assert an identity of its own.
- **The bearer token never reaches browser JavaScript.** The BFF keeps the JWT in an
  httpOnly, SameSite=Lax cookie and calls the gateway server-side.
- **Ownership is checked against stored records.** Each service authorizes through
  `AccessGuard` against the owner recorded with the account, payment, loan, card or
  application, never an id taken from the request. Staff can read, deposit and review,
  but cannot take money out of a customer's account, accept an offer on a customer's
  behalf, or decide about themselves.
- **Sign-in is hardened.** BCrypt password hashes, TOTP two-factor authentication, and a
  per-account login throttle in Redis that fails closed, alongside the gateway's per-IP
  rate limit.
- **Money integrity is enforced in the database.** Idempotency records under a unique
  constraint, row locks on balances, and outbox rows written in the same transaction as
  the change they announce.
- **Business services are private.** They publish no host ports and stay on the
  private service network, reachable only through explicit gateway `/api/**` routes or
  internal service paths; the `/internal/**` service-to-service endpoints are never
  routed from outside.
- **Code and supply-chain scanning.** CodeQL over Java and TypeScript; Trivy over
  dependencies, Dockerfiles, the base image and the Terraform.

The threat model, the authorization rules principal by principal, sensitive-data
handling, and how scanner findings are assessed are in
**[docs/SECURITY.md](docs/SECURITY.md)**.

## Reliability & Observability

- Consumers claim each event id under a unique constraint in the same transaction as
  their work, so redelivery is harmless; retries are bounded, and
  exhausted records land on a `.DLT` topic with their failure context.
- Kafka producers fail fast (`max.block.ms` of one second), so a broker outage cannot
  hang request threads while a balance row is locked.
- Every service exports Micrometer metrics to Prometheus, read by a provisioned Grafana
  dashboard, and traces to Zipkin. An `X-Request-Id` minted at the gateway follows a
  request across every REST and Feign hop.

Details: [docs/EVENTS.md](docs/EVENTS.md) and [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md).

## Testing

| Suite | Where it runs | Count |
|---|---|---:|
| Backend unit and web-slice | CI | 870 |
| Backend integration (Testcontainers, PostgreSQL, Redis, embedded Kafka) | CI | 158 |
| Frontend unit and component | CI | 385 |
| Playwright against a production build, no backend | CI | 69 |
| **Total in CI** | | **1,482** |
| Playwright against the full running stack | on demand | 46 |
| PowerShell full-stack suite (assertions) | on demand | 200 |

The live suites need all 13 backend processes running, so they are run on demand rather
than on every push. See [docs/TESTING.md](docs/TESTING.md).

## Technology

| | |
|---|---|
| **Backend** | Java 21, Spring Boot 3.3, Spring Cloud Gateway, Eureka, OpenFeign, Resilience4j |
| **Frontend** | Next.js 16, React 19, TypeScript, Tailwind CSS 4 |
| **Data and messaging** | PostgreSQL 16 with Flyway, Redis 7, Apache Kafka |
| **Security** | JWT at the gateway, BCrypt, TOTP two-factor, per-resource ownership checks |
| **Observability** | Micrometer, Prometheus, Grafana, Zipkin, `X-Request-Id` correlation |
| **Testing and delivery** | JUnit 5, Testcontainers, Vitest, Playwright, GitHub Actions, CodeQL, Trivy, Docker Compose, Terraform |

## Project scope

Runs locally on Docker Compose with synthetic financial data and simulated wire, ACH
and SWIFT rails; the AWS topology is a Terraform-defined reference and is not currently
deployed. No production, regulatory or compliance claim is made.

## Run locally

Prerequisites: JDK 21+, Maven 3.8+, Docker Desktop.

```bash
git clone https://github.com/Kalab21/banking-platform.git
cd banking-platform
cp .env.example .env      # then set the values
mvn clean package         # build the service jars
docker compose up -d      # infrastructure, gateway, Eureka and 11 services
./scripts/seed-demo.sh    # optional: a synthetic customer; credentials are printed
```

| Endpoint | URL |
|---|---|
| **Banking console** | **<http://localhost:3000>** |
| API gateway | <http://localhost:8080> |
| Eureka dashboard | <http://localhost:8761> |
| Kafka UI | <http://localhost:8095> |

IDE-based setup, frontend-only development, the API reference and troubleshooting are
in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Documentation

| Document | What it covers |
|---|---|
| [Architecture](docs/ARCHITECTURE.md) | Topology, service boundaries, data ownership, design decisions |
| [Security](docs/SECURITY.md) | Threat model, authentication, authorization, sensitive data, dependency security |
| [Testing](docs/TESTING.md) | Strategy, suites, counts, commands |
| [Events](docs/EVENTS.md) | Kafka contracts, delivery, retry, dead-letter topics, idempotency |
| [Observability](docs/OBSERVABILITY.md) | Metrics, tracing, dashboards |
| [Development](docs/DEVELOPMENT.md) | Local setup, ports, commands, troubleshooting |
| [Product experience](docs/PRODUCT-EXPERIENCE.md) | Money movement, credit application, offers and servicing, screen by screen |
| [AWS infrastructure](infrastructure/aws/) | Terraform for the AWS reference model |

## Copyright & Usage

© 2026–present Kalabe Kebede. All Rights Reserved.

Northbank is proprietary software, published for demonstration and technical
evaluation. It is not open source. No permission is granted to reuse, modify,
redistribute or commercially exploit its original material without written permission.
Third-party components remain under their own licences.

See [LICENSE](LICENSE) for the full terms.
