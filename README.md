# Northbank

**Event-Driven Retail Banking Platform**

Northbank is a retail banking platform built from Java 21 / Spring Boot microservices
behind a Spring Cloud Gateway and a Next.js backend-for-frontend, with Kafka for
derived workflows, a PostgreSQL database per service and Redis, designed so that money
movement stays correct under retries, concurrency and partial failure.

[![CI](https://github.com/Kalab21/banking-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/Kalab21/banking-platform/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Kalab21/banking-platform/actions/workflows/codeql.yml/badge.svg)](https://github.com/Kalab21/banking-platform/actions/workflows/codeql.yml)
[![Security Scan](https://github.com/Kalab21/banking-platform/actions/workflows/security-scan.yml/badge.svg)](https://github.com/Kalab21/banking-platform/actions/workflows/security-scan.yml)

> Runs on synthetic data. It moves no real money, the wire, ACH and SWIFT rails are
> simulated, and it makes no production, regulatory or compliance claim.

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

## Architecture

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/architecture/northbank-logical-dark.svg">
  <img src="docs/architecture/northbank-logical.svg" width="1000" alt="Northbank logical architecture in five layers. Public: customers and staff in a web browser that holds no bearer token. Application edge: the Next.js backend-for-frontend keeps the JWT in an httpOnly cookie and calls the API gateway server-side over REST; the Spring Cloud Gateway validates the JWT, overwrites the X-User identity headers, rate-limits with Redis and discovers services through Eureka. Private service network: eleven Spring Boot services grouped by domain. Identity: user-service with sign-in, JWT issuing, TOTP, BCrypt, login throttling and KYC. Accounts and money movement: account-service, the only writer of balances, called by transaction-service through OpenFeign with a circuit breaker, plus payment-service and integration-service. Lending and cards: application-service, loan-service and credit-card-service, which call account-service over REST and exchange provisioning events over Kafka. Risk and insight: fraud-detection, statistics-service and notification-service, which consume Kafka events. Data and messaging: Redis for rate limits, login throttling, fraud velocity and the statistics cache; PostgreSQL 16 with one database per service; Apache Kafka with a transactional outbox, idempotent consumers, retry and dead-letter topics, carrying derived state only. Operations: Micrometer, Prometheus, Grafana, Zipkin and X-Request-Id; GitHub Actions, CodeQL, Trivy, Docker Compose and Terraform.">
</picture>

Requests enter through the Next.js backend-for-frontend, which makes every banking API
call server-side. The API gateway is the only routed entry to the service network: it
authenticates the caller and forwards an identity it derived itself. Services own their
data, call each other over REST when they need an answer now, and publish Kafka events
for everything that can lag.

**Key design decisions**

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

The service-level diagram (every OpenFeign call, every topic, ports and databases), data
ownership and the full set of design decisions are in
**[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

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

The dashed zones in the diagram are the trust boundaries: the public browser, the
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
- **Business services are private.** In Docker Compose they publish no host ports, and
  the gateway routes only explicit `/api/**` paths, so the `/internal/**`
  service-to-service endpoints are unreachable from outside. Internal calls rely on that
  network isolation, not on mTLS or workload identity.
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

## Product experience

![The customer dashboard: total balance, account cards, balance history, recent activity and credit, loan and security summaries](docs/screenshots/13-dashboard-desktop.png)

| Move money: review before confirming | Explore credit |
|---|---|
| ![The review step naming the amount and both accounts by their last four digits, above one confirm button](docs/screenshots/23-move-money-review.png) | ![Four credit products, what each application asks, and what happens after applying](docs/screenshots/26-explore-credit.png) |

| Guided credit application | An application and its history |
|---|---|
| ![The last of three steps, repeating every answer with a way to change it](docs/screenshots/27-credit-application.png) | ![An application's own page with its history from stored timestamps, and the offer's terms above Accept and Decline](docs/screenshots/33-application-detail.png) |

| Paying a loan | Staff review of a referred application |
|---|---|
| ![A loan offering the rest of a part-paid instalment, another amount, or payoff at today's figure](docs/screenshots/34-loan-payment.png) | ![A referred application with the applicant, what they stated, the policy's reason code, and Approve or Reject](docs/screenshots/31-staff-review-workbench.png) |

The full set is in [`docs/screenshots/`](docs/screenshots/).

### The credit journey

```text
Explore credit → Apply (3 steps) → Underwrite → Staff review if referred
      → Offer → Customer accepts or declines → Provision → Service the loan or card
```

- **Underwriting** is a deterministic, versioned policy: score, debt-to-income,
  loan-to-value, amount and term, and identity check. Each decision is stored as an
  immutable record with its reason codes.
- **An offer's terms are stored once.** Only the applicant can accept or decline, and
  accepts exactly those terms; staff and admins cannot act on the customer's behalf.
- **Provisioning happens once.** The card or loan service creates the product from the
  accepted terms and confirms its real ID.
- **Servicing is in the console**: receive a loan, pay an instalment or any amount, pay
  the loan off, and pay a card.

## AWS Reference Deployment

Terraform-defined reference architecture; not currently deployed.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/architecture/northbank-aws-reference-dark.svg">
  <img src="docs/architecture/northbank-aws-reference.svg" width="1000" alt="Northbank AWS reference deployment from the Terraform in infrastructure/aws, not currently deployed. Clients resolve the API name through Route 53, which aliases it to CloudFront. CloudFront terminates HTTPS with an ACM certificate (TLS 1.2 minimum), has an AWS WAF web ACL attached (common, SQL injection and known-bad-input managed rules, an /api/auth rate limit and a geo block), and forwards to the origin over HTTPS only. The internet-facing Application Load Balancer in the public subnets has an HTTPS listener and an HTTP listener that redirects to HTTPS, and forwards to the API gateway target group over HTTP. In the private subnets an ECS Fargate cluster with no public IPs runs api-gateway, eureka-server registered in Cloud Map private DNS, and the eleven business services. Tasks reach RDS PostgreSQL 16 (Multi-AZ, storage encrypted) over JDBC, ElastiCache Redis 7 (single node), and Amazon MSK (two brokers, TLS between clients and brokers, KMS key at rest). A NAT gateway provides egress. Regional services: ECR, Secrets Manager for the database password and JWT secret, and CloudWatch Logs, reached through VPC endpoints, plus IAM task roles and the KMS key.">
</picture>

[`infrastructure/aws/`](infrastructure/aws/) describes how the platform would run on AWS:

- **Edge**: Route 53 → CloudFront with an ACM certificate and AWS WAF → an
  internet-facing ALB. Viewer and origin connections are HTTPS (TLS 1.2+); the ALB
  redirects HTTP to HTTPS and forwards to the gateway task over HTTP inside the VPC.
- **Compute**: the gateway, Eureka (via Cloud Map private DNS) and the 11 services as
  ECS Fargate tasks in private subnets with no public IPs, images from ECR, secrets from
  Secrets Manager, logs to CloudWatch.
- **Data**: RDS PostgreSQL 16 (Multi-AZ, encrypted storage), ElastiCache Redis 7, and
  Amazon MSK with TLS between clients and brokers and a customer-managed KMS key at
  rest. Security groups admit database, cache and broker traffic only from the ECS
  tasks. TLS is not configured for the RDS or ElastiCache connections.

Gaps a real rollout would close first: the Next.js console has no task definition here;
the ECS tasks are given MSK's plaintext bootstrap list although the brokers accept TLS
only; `user-service` is not given the Redis endpoint its login throttle needs; and the
ALB does not yet require the `X-Origin-Verify` header CloudFront adds, so it can be
reached directly.

## Project scope

Northbank runs locally on Docker Compose with synthetic data. It is not connected to
real payment networks, no hosted instance is published, and it makes no regulatory or
compliance certification claim.

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
| [Diagrams](docs/diagrams/generate_diagrams.py) | Generator for the logical and AWS reference diagrams |

## Copyright & Usage

© 2026–present Kalabe Kebede. All Rights Reserved.

Northbank is proprietary software, published for demonstration and technical
evaluation. It is not open source. No permission is granted to reuse, modify,
redistribute or commercially exploit its original material without written permission.
Third-party components remain under their own licences.

See [LICENSE](LICENSE) for the full terms.
