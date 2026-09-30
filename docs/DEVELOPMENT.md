# Development

Setup options beyond the default Docker Compose run, the API reference, and
troubleshooting.

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
accounts, twelve transactions and a beneficiary; submits two KYC documents and
has the demo reviewer approve them; then applies for a loan and a credit card,
accepts both offers and waits for the products to be created, disbursing and
repaying the loan once and putting three purchases and a payment on the card.
It needs the demo staff account (`NORTHBANK_DEMO_STAFF_*`, enabled in
`docker-compose.yml`). Names and numbers are synthetic, and re-running creates a
fresh customer.

`SEED_BACKDATE=1` additionally spreads the dates, which is the one step that
writes to the databases directly: `created_at` is a `@CreationTimestamp` and is
deliberately not settable through the API. Transactions are spread over the
preceding eight weeks with the two legs of a transfer kept on the same
timestamp, the accounts are opened before their first transaction, and the
customer is registered before their accounts. It exists so screenshots have a
real date range, it is off by default, and it needs the Compose stack.

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

## Running the tests

```bash
mvn -B --no-transfer-progress clean verify     # backend unit and integration; Docker must be running
cd frontend
npm ci && npm run lint && npm run typecheck    # static checks
npm run test && npm run build && npm run test:e2e   # unit, production build, offline Playwright
```

The full-stack suites need the Compose stack running and a seeded customer:
`.\e2e-tests.ps1` from the repository root, and the live Playwright project from
`frontend/`. Commands, suites and counts are in [TESTING.md](TESTING.md#commands).

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

The API docs are off in the normal stack (see GHSA-rhhx-6j8h-8cvw in
[SECURITY.md](SECURITY.md#dependency-and-security-scanning)). Starting with
`docker-compose.dev-ports.yml` turns them on for each service's local port:

- Swagger UI — `http://localhost:<service-port>/swagger-ui.html`
- OpenAPI JSON — `http://localhost:<service-port>/v3/api-docs`

The gateway never serves them.

Selected routes, all reached through the gateway on `:8080`:

| Method | Route | Purpose |
|---|---|---|
| `POST` | `/api/auth/register`, `/api/auth/login` | Obtain a JWT |
| `POST` | `/api/auth/2fa/setup`, `/api/auth/2fa/verify` | TOTP enrolment and verification |
| `GET` `POST` | `/api/accounts` | Open and list accounts |
| `POST` | `/api/transactions/deposit`, `/withdraw`, `/transfer` | Money movement — requires `Idempotency-Key`; withdraw and transfer-out are the account owner's only |
| `POST` | `/api/payments`, `/api/payments/beneficiaries` | Payments (the payer account's owner only; requires `Idempotency-Key`) and beneficiaries (owner or staff) |
| `POST` | `/api/applications` | Apply for an account, card or loan (for self; staff for anyone) |
| `GET` | `/api/applications/{id}/offers` | The offer on an application (owner or staff) |
| `POST` | `/api/applications/{id}/offer/accept`, `/offer/decline` | Answer the offer — the applicant only, no request body |
| `PUT` | `/api/applications/{id}/review` | Decide a referred application — employee/admin only, never their own |
| `GET` | `/api/applications/{id}/decisions` | Every decision on an application, with its reason codes — employee/admin only |
| `GET` `POST` | `/api/loans/{id}/...`, `/api/credit-cards/{id}/...` | Reads (detail, schedule, repayments, statements) are owner or staff. Disburse, repay, payoff, cash advance and card payment are the borrower's or cardholder's only; repay, payoff and the card writes require `Idempotency-Key`. Purchase is staff-only (simulated merchant) |
| `GET` | `/api/statistics/users/{id}` | A customer's own read models (owner or staff) |
| `GET` | `/api/statistics/platform`, `/api/statistics/daily` | Platform-wide read models — employee/admin only |
| `GET` | `/api/notifications` | Paginated user alerts (owner or staff) |
| `GET` | `/api/fraud/alerts` | Fraud alerts — list, read and review, all employee/admin |
| `POST` | `/api/integrations/wire-transfer`, `/ach-transfer`, `/swift-transfer` | External rails (simulated: recorded, not settled; no balance is debited) |

## Direct service access

The default stack publishes only the console, the gateway, Eureka, Kafka UI and
the infrastructure containers. The business services listed below are reachable
only on the Compose network, so application traffic has to pass the gateway.

To reach one directly — a debugger, its Swagger UI, an actuator endpoint:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d
```

That override bypasses gateway authentication, so use it only locally.

## Service inventory and design decisions

The service inventory, data ownership, Kafka topics and the design decisions
behind them are in [ARCHITECTURE.md](ARCHITECTURE.md).
