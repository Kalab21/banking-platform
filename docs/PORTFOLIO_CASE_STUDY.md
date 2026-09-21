# Northbank Banking Platform

## Overview

Northbank is a thirteen-process Spring Boot banking platform behind a Next.js
customer console, built to demonstrate and validate difficult financial-system
concerns: concurrent balance updates, idempotent money movement,
resource-level authorization, failure handling and secure customer-data
boundaries.

It uses synthetic data and makes no production or regulatory claim.

**At a glance:** 13 backend processes (Eureka, the API Gateway and 11 business
services), 1010 automated tests in CI, 34 live-stack scenarios on demand, and a
customer console that never holds a bearer token or a full account number.

## Problem / Context

The interesting problems in retail banking software are not the entities, they
are the failure modes: two debits arriving at once, a request whose response is
lost after the money moved, an id in a URL that belongs to another customer, a
page that renders `$0.00` because a service was unreachable.

Northbank was built to implement those cases properly, to prove each one with a
test that fails when the protection is removed, and to state plainly which ones
it does not solve. It moves no real money, holds no real customer data, and
makes no compliance claim.

## Architecture

![Northbank system architecture](architecture/northbank-system-architecture.svg)

Thirteen backend processes — Eureka, the API Gateway and 11 business services —
plus a Next.js console:

- **Edge** — Spring Cloud Gateway. Validates the JWT, derives identity from it,
  and injects `X-User-Id` / `X-User-Role` downstream, replacing anything the
  client sent under those names. Redis token-bucket rate limiting per client IP.
- **Service registry** — Eureka; the gateway routes by service name.
- **Business services (11)** — `user`, `account`, `transaction`, `payment`,
  `loan`, `credit-card`, `application`, `statistics`, `notification`,
  `fraud-detection`, `integration`. Each owns its schema in its own PostgreSQL
  database.
- **Messaging** — Kafka for derived state: statistics, notifications, fraud
  scoring, card and loan issuance from approved applications.
- **Console** — Next.js App Router. React Server Components fetch; Server
  Actions mutate. The browser never holds a bearer token.
- **Observability** — Micrometer to Prometheus, Grafana dashboards, Brave
  tracing to Zipkin, a correlation id minted at the edge and carried through
  synchronous and asynchronous hops.

Business services publish no host ports. Application traffic has to pass the
gateway, which is what makes the `/internal` endpoints internal.

### The console as a backend-for-frontend

The session is a JWT in an httpOnly, SameSite=Lax cookie written by a Server
Action. Page JavaScript cannot read it, which removes the XSS token-theft route
that `localStorage` would open. The cookie's lifetime comes from the token's own
expiry, so the browser drops it exactly when the backend stops honoring it.

The role in the token drives navigation only. It is never an authorization
decision: the backend re-verifies the signature at the gateway and enforces
ownership in the services. If the two disagree, the backend wins and the
customer sees a 403.

## My Engineering Work

My engineering work across Northbank focused on the distributed-system, security,
correctness and customer-experience concerns that determine whether financial
workflows behave safely under failure.

The work that took the most judgment, rather than the most typing:

- Designing the money-movement contract — one logical operation, one idempotency
  key, three possible outcomes — and making the console honor it.
- Finding and closing two rounds of broken object-level authorization, the
  second in services that had no authorization code at all, and proving the fix
  at the HTTP boundary rather than in the service layer.
- Making concurrent balance changes correct under a real database, then writing
  the tests that fail if the row lock is removed.
- Removing raw account numbers from the React Server → Client boundary, a leak
  that is invisible on screen and plain in view-source.
- Rewriting an end-to-end suite that had been reporting failures while exiting
  zero, including two mechanisms that produced false passes.

## Key Engineering Decisions

### Resource ownership, and finding where it was missing

The rule is simple to state: an id in a path or a `userId` in a body is caller
input. It says which resource is wanted; it never says who is entitled to it.
Every handler resolves the owner from stored state and authorizes against that.

Applying it took two passes, and both were found by running the full stack
rather than by reading code.

The first pass covered `fraud-detection`, `payment`, `notification` and
`application`, which read the `userId` from the request and acted on it, so any
customer could reach another customer's payees, notifications and applications,
and could list and resolve fraud alerts platform-wide.

The second pass covered `loan-service` and `credit-card-service`, which were
worse: neither had the shared security module on its classpath at all, so no
handler ever saw a caller identity and nothing was checked. A freshly registered
second customer could read another customer's loan, interest rate and full
amortization schedule, and that customer's card balance, credit limit, APR,
rewards and transaction history. The write paths were open the same way —
repayment, early payoff, disbursement, purchase, cash advance, card payment and
freeze.

Both are fixed, and each service carries a regression suite written at the HTTP
boundary, because the boundary is the control: every negative case asserts both
that the response is 403 and that the service layer was never reached.

### Idempotency, on both sides of the wire

`POST /api/transactions/deposit`, `/withdraw` and `/transfer` require an
`Idempotency-Key`: an opaque, client-generated value naming one logical
operation. It is deliberately not derived from the amount, the accounts or the
time, because two identical transfers a minute apart must both be able to
succeed.

`transaction-service` records each key under a unique constraint with a SHA-256
fingerprint of the caller and the normalised request. The constraint is the
mechanism: two concurrent duplicates both attempt the insert, the database
admits one, and the loser resolves against the winner's row rather than calling
`account-service` again.

The outcomes are distinguished carefully. A refusal that provably moved no money
releases the key, because caching a rejection would lock a client out of an
operation it is entitled to retry. A failure that reached `account-service` and
then lost the thread spends the key permanently, because whether the balance
changed is not knowable and a retry would be a coin-flip between a no-op and a
second debit.

None of that is worth anything if the client mints a new key per attempt, which
is exactly what the console did at first — one per server-action invocation, so
every resubmission was a different logical operation and the contract never
applied. The browser now mints one opaque id when the customer reaches the
review step and sends it with every attempt at that payment. Only starting a new
payment mints a new one.

### Pessimistic locking on balance changes

Every path that changes a balance loads the account with `SELECT ... FOR
UPDATE`. Reading the balance, deciding whether it is sufficient and writing the
new figure all happen with the row locked, so two concurrent debits are applied
one after the other rather than both against the same starting balance.

Pessimistic rather than optimistic, deliberately: `@Version` also prevents the
lost update, but by failing the loser with an exception that must be caught,
re-read and replayed — and the replay has to re-run the overdraft rules, because
the answer depends on the balance it now sees. A row lock gives the same
correctness with no retry loop and no path where a debit is silently attempted
twice. A Testcontainers test fires concurrent debits at a real PostgreSQL and
fails if the lock is removed.

### Telling the truth about unknown outcomes

A money request has three outcomes, not two. The third is one whose result the
platform cannot establish, and flattening it into "failed" is how a customer
sends the same money twice.

The console follows the backend's contract exactly. 503 is the only server
status treated as a definite "nothing happened", because it is the only one that
promises it: the circuit was open and the call never left the service. 504, a
bare 500, a transfer that debited and failed to credit, and any transport
failure are all treated as unknown.

For an unknown outcome the console claims neither success nor failure, offers no
control that would resend, does not mint a fresh key, and points the customer at
their transaction history — the one place that settles the question. The wording
is part of the control: an invitation to try again is an invitation to move the
money a second time.

### Data minimization at the Server/Client boundary

React Server Components make this a real boundary. Anything a Server Component
passes to a Client Component is serialized into the RSC payload inside the HTML,
and again into the Flight response on a client-side navigation — readable in
view-source whether or not it is ever rendered.

The money forms were a Client Component taking the account records, so the raw
account number shipped to the browser on every visit even though every label on
screen was masked. Masking inside the component would have been too late. The
server now builds a narrowed view model — id, ready-made label, masked number,
currency, available balance — and the account number stays on the server.

Guarded against the three surfaces a browser actually sees: the delivered HTML,
the RSC response captured during a real client navigation, and the DOM including
`aria-label`, `title` and `data-*` attributes. The tests read the account
numbers back from the API for the signed-in customer rather than using a
fixture, and never print the value when they fail.

### SSN minimization

Of the Social Security number given at onboarding, only the last four digits are
kept: the server checks the format, derives those four digits and discards the
rest. No column holds the whole number and no endpoint returns one. Those four
digits live on a separate `customer_identity` row rather than on the user row,
so the entity the profile response maps from has nothing sensitive to leak. The
field is write-only in JSON and excluded from the request's `toString()`.

There is deliberately no digest of the full number: a hash of a nine-digit value
with known structure is enumerable in seconds, so storing one would be storing
the number with extra steps.

## Customer Experience

The console covers dashboard, accounts and account detail, move money,
transactions, payments, credit cards and card detail, loans and loan detail,
notifications, and profile and security — plus staff views for KYC review, the
application queue and fraud alerts.

Moving money is its own route and its own journey: choose transfer, deposit or
withdrawal, fill in the details, review exactly what is about to happen against
masked accounts, confirm once, and get a receipt carrying the reference the
backend actually issued. Nothing on the receipt is invented.

Onboarding is a five-step wizard — sign-in, personal, address, identity, review
— with a live password checklist that shows the rule the server enforces, and a
review step that masks the identity number and reports the password as "Set"
rather than showing anything.

Presentation follows from what the data supports. The card face carries no
network logo, expiry or CVV, because the API has none of them and a card face
showing an invented expiry is a lie in the shape of a UI. A loan shows "balance
repaid" rather than "principal repaid", because the record exposes principal and
remaining balance but not the split, and calling the difference principal would
attribute interest payments to it. Direction on a transaction is carried three
ways — an arrow, an explicit sign, and the wording of the type — so it never
depends on color alone.

Every page distinguishes "there is nothing here" from "we could not load this".
A dashboard that renders zeroes after a failed request has told the customer
something false about their money.

## Verification

1010 automated tests run in CI:

| Suite | Count |
|---|---|
| Backend unit and web-slice (JUnit 5, Mockito, MockMvc) | 589 |
| Backend integration against real PostgreSQL and an embedded Kafka broker | 139 |
| Frontend unit and component (Vitest, React Testing Library) | 213 |
| Offline end-to-end (Playwright, production build, no backend) | 69 |

On demand, against the full running stack: 34 live Playwright scenarios and a
PowerShell suite that drives registration through to TOTP enrollment.

Counts are test cases as the runners report them, not assertions.

What the harder suites actually assert: concurrent debits against a real
database serialize without a lost update; concurrent duplicate money movement
executes once and replays the stored result; forged identity headers at the
gateway are replaced; a denial returns 403 *and* never reaches the service; one
customer operation sends one idempotency key however many times it is attempted;
a double-clicked confirm moves money once; and the account number appears in
none of the three surfaces a browser can read.

## Security & Correctness

- JWT issued at login, validated at the gateway, never re-verified downstream
- Identity derived from the token and injected downstream, replacing client input
- Ownership enforced per request against an owner resolved from stored state
- BCrypt passwords; minimum eight characters with upper, lower and a digit
- TOTP two-factor (RFC 6238); with it enabled, a correct password alone issues no token
- Full PAN never leaves the service boundary; responses carry a mask and `last4`
- Second-factor enrolment, confirmation and removal are self-only; no role manages another account's
- External wire / ACH / SWIFT transfers may only be sent from an account the caller owns
- Session in an httpOnly cookie, unreadable by page JavaScript
- Signing key required from the environment; services refuse to start without it
- CodeQL on Java and TypeScript, Trivy over dependencies, Dockerfiles and base image

## Tradeoffs

**Pessimistic over optimistic locking** — a waiting transaction rather than a
retry loop, chosen because the replay would have to re-run the overdraft rules.

**Synchronous calls for authoritative state, events for derived state** — a
transfer must know immediately whether the debit succeeded, so that is a Feign
call. Statistics, notifications and fraud scoring tolerate lag, so they consume
Kafka.

**No retry on the circuit breaker** — idempotency makes a *client's* repeat
safe; it does not make an automatic in-process retry of a half-completed
downstream mutation safe.

**Money-moving writes limited to deposit, withdrawal and transfer** — payment
creation, scheduled payments, loan repayment and card payment all exist in the
backend, but none of those endpoints requires an idempotency key the way the
transaction endpoints do. A console flow for them would be the one money path
where a lost response could not be retried safely, so they are deliberately
absent rather than half-built.

## Known Limitations

These are recorded rather than solved, and each is a deliberate stopping point.

1. **A transfer is not atomic across services.** The debit and the credit are
   two calls to `account-service`. If the credit fails after the debit is
   applied, the transfer is reported as needing reconciliation and the
   idempotency record settles unknown so no retry can debit twice. There is no
   saga and no compensating transaction.
2. **Unknown outcomes are not reconciled automatically.** They are logged for a
   human. Nothing reads that log.
3. **No Kafka dead-letter handling.** A poison message hits Spring Kafka's
   default retry-then-log behavior and is dropped.
4. **Service-to-service calls are not authenticated.** The `/internal`
   endpoints rely on network isolation — no host ports, no gateway route —
   rather than mTLS or a service credential.
5. **Circuit-breaker coverage is partial.** Only the
   `transaction-service` → `account-service` hop is protected.
6. **Observability stops short of operations.** No log aggregator, traces held
   in memory and lost on restart, no alerting rules and no route for one to
   fire down.
7. **External rails are simulated.** Wire, ACH and SWIFT are modeled, not
   connected to anything.
8. **Test depth is uneven.** Three services run against a real PostgreSQL;
   `payment`, `notification`, `integration` and `application` have
   authorization suites but no service-layer tests.
9. **The live suite runs on demand.** Starting thirteen backend processes on
   every push is not a sensible trade, so only the offline suite is wired into
   CI.
10. **No per-account login throttling.** The only limit is the gateway's
    per-IP rate limit, which does not stop a distributed attempt on one account.

## Technology

Java 17, Spring Boot 3.3, Spring Cloud Gateway, Spring Security, Spring Data JPA,
Feign, Resilience4j, PostgreSQL 16, Kafka, Redis, Eureka, Flyway, MapStruct,
Lombok, JUnit 5, Mockito, Testcontainers, Micrometer, Brave, Prometheus,
Grafana, Zipkin.

Next.js 16 (App Router), React 19, TypeScript 5, Tailwind CSS 4, Recharts, Zod,
Vitest, React Testing Library, Playwright.

Docker Compose, GitHub Actions, CodeQL, Trivy.

## Screenshots

The full set is in [`screenshots/`](screenshots/); every image is captured by Playwright
against the running stack with a seeded synthetic customer.

| File | What it demonstrates |
|---|---|
| `01-login-desktop.png` | Sign-in, brand panel, the product's visual language |
| `03-register-desktop.png` | Onboarding step one with the live password checklist |
| `11-onboarding-review.png` | Review step: masked identity number, password shown as "Set" |
| `13-dashboard-desktop.png` | Total balance, account cards, real balance history, recent activity |
| `14-dashboard-mobile.png` | The same information on a phone, no horizontal scroll |
| `15-accounts.png` | Accounts with masked numbers, balance and available balance |
| `16-account-detail.png` | One account's real transaction history with running balance |
| `22-move-money-details.png` | Choosing an action and entering the details |
| `23-move-money-review.png` | What is about to happen, against masked accounts |
| `24-move-money-receipt.png` | The backend's own reference, nothing invented |
| `17-transactions.png` | Activity across accounts, direction carried without color |
| `18-cards.png` | Card with utilization, APR, rewards — and no invented expiry or CVV |
| `19-loans.png` | Balance progress, monthly payment, next payment date |
| `20-profile-security.png` | Profile with masked SSN and honest identity status |
| `25-payments.png` | Payees, saved through the console, displayed masked |

## Interview Talking Points

- Found and fixed two rounds of broken object-level authorization, the second in
  services that had no authorization code at all; confirmed live with a second
  customer before writing the fix, and covered it with HTTP-boundary tests that
  assert the service is never reached.
- Made an idempotency contract actually hold by fixing the client: the key is
  minted per logical operation, not per attempt.
- Handled the third outcome — a money request whose result is unknown — with
  wording and UI that will not cause a second payment.
- Stopped raw account numbers reaching the browser through the RSC payload, a
  leak that is invisible on screen and obvious in view-source.
- Used a pessimistic row lock for concurrent balance changes, and proved it with
  concurrent writes against a real PostgreSQL.
- Reduced a Social Security number to four digits at the boundary and argued
  against storing a hash of the rest.
- Corrected a test suite that had been printing failures and exiting zero, and
  replaced fixed sleeps for Kafka with bounded polling.
