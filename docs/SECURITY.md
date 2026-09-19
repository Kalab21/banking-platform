# Security model

How a request is authenticated, how it is authorised, and what is not covered.

This is a portfolio project. It handles no real money and holds no real customer
data, and it makes no regulatory or certification claims.

## Authentication

`user-service` issues a JWT on login, signed HS256. The gateway validates the
signature and expiry on every non-public route.

Having validated the token, the gateway derives the caller's identity from it and
writes three headers on the forwarded request:

```
X-User-Id      from the token's userId claim
X-Username     from the subject
X-User-Role    from the roles claim
```

These **overwrite** anything the client sent. A request arriving with
`X-User-Role: ADMIN` and a customer's token reaches the service as that customer.
This is the property the rest of the model depends on, and it is covered by
`GatewayIdentitySpoofingTest`, including under varied header casing.

Public routes — `/api/auth/register`, `/api/auth/login`, actuator and the API docs
— skip the filter and establish no identity. Protected routes in the services
reject identity-less requests, so a public path is not a way in.

## Authorization

Services consume the identity the gateway established; they do not re-verify the
token. Authorization is applied per request against the resource's owner.

`AccessGuard` in `common-security` holds every rule as a named method. The rules
throw rather than return a boolean, because a caller that forgets to branch on a
boolean fails open.

| Rule | Meaning |
|---|---|
| `requireOwnerOrStaff` | the subject, or any employee/admin |
| `requireSelf` | the subject only; staff do not bypass |
| `requireStaff` | employee or admin |
| `requireAdmin` | admin only |
| `requireTargetUserAllowed` | a customer may act only for themselves; staff for anyone |

### What each principal may reach

| | Customer (own) | Customer (another's) | Employee | Admin |
|---|---|---|---|---|
| Accounts, transactions, profile, KYC, per-user statistics | yes | **no** | yes | yes |
| Payees, payments, notifications, applications | yes | **no** | yes | yes |
| Loans: detail, schedule, repayments, payoff quote | yes | **no** | yes | yes |
| Loans: repay, early payoff, disburse | own only | **no** | yes | yes |
| Cards: detail, transactions, statements | yes | **no** | yes | yes |
| Cards: purchase, cash advance, payment, freeze | own only | **no** | yes | yes |
| Open an account, submit an application | for self | **no** | for anyone | for anyone |
| Move money, pay from an account | from own accounts | **no** | — | — |
| Cancel an application, remove a payee | own only | **no** | yes | yes |
| Freeze account, overdraft limit | **no** | **no** | yes | yes |
| Platform and daily statistics | **no** | **no** | yes | yes |
| KYC review, credit-score update | **no** | **no** | yes | yes |
| Application queue by status, manual decision | **no** | **no** | yes | yes |
| Fraud alerts: list, read, review | **no** | **no** | yes | yes |

An id in a path or a `userId` in a request body is caller input. It is checked
against the identity the gateway established, never trusted as proof of ownership.

That rule was applied unevenly at first, and it took two passes to finish.

The first pass covered `fraud-detection-service`, `payment-service`,
`notification-service` and `application-service`. Each read the `userId` from
the path, body or query string and acted on it directly, so any authenticated
customer could reach another customer's payees, notifications and applications,
and could list, read and resolve fraud alerts across the whole platform.

The second pass covered `loan-service` and `credit-card-service`, which were
worse: neither had `common-security` on its classpath at all, so no handler in
either service ever saw a `CallerIdentity` and nothing was checked. Confirmed
against the running stack rather than inferred — a freshly registered second
customer could read another customer's loan, interest rate and full
amortization schedule, and that customer's card balance, credit limit, APR,
rewards and transaction history, by asking for an id it did not own. The write
paths were open the same way: repayment, early payoff, disbursement, purchase,
cash advance, card payment and freeze.

Running the full stack is what surfaced both rounds; the unit suites at the time
asserted nothing about those services. All six now resolve the owner from stored
state and authorise against it, the same way the account and transaction
services do, and each has a regression suite that fails if the guard is removed.

Fraud alerts are staff-only in every direction. An alert is a control applied to
a customer, so the customer it names is not among the principals who may read or
close it — and the reviewer recorded against a decision is taken from the caller
the gateway authenticated, never from the request body.

Denials return 403 and are refused before the service layer runs, so a rejected
request writes nothing and moves no money. A request that establishes no identity
returns 401.

## Internal boundary

Direct balance mutation — arbitrary credit or debit — is a service-to-service
operation with no customer-facing equivalent. It lives on `/internal/accounts/**`
along with the fraud-driven account freeze, which runs from a Kafka listener and
therefore has no caller identity to authorise.

The boundary is the network:

- the gateway declares explicit `/api/**` routes only, so `/internal` is not routed
- the discovery locator is disabled; enabled, it would auto-create
  `/{service-id}/**` routes and expose `/account-service/internal/**`
- the business services publish no host ports, so they are reachable only on the
  Compose network

`GatewayRouteExposureTest` asserts the first two. The third is a Compose property,
and `docker-compose.dev-ports.yml` exists to re-open those ports deliberately when
developing rather than by default.

## Other controls

| Control | Implementation |
|---|---|
| Password storage | BCrypt |
| Two-factor | TOTP (RFC 6238); with 2FA enabled, a correct password alone issues no token |
| Card data | The full PAN never leaves the service boundary; responses carry a masked value and `last4` |
| Identity number at onboarding | The Social Security number is read from the request, checked for format, reduced to its last four digits and dropped. Those four digits live in `customer_identity` rather than on the user row, so the entity `UserResponse` maps from has nothing sensitive to leak. The field is `@JsonProperty(access = WRITE_ONLY)` and excluded from the request's `toString()`, and validation messages name the rule rather than quoting the value |
| Browser session | JWT in an httpOnly, SameSite=Lax cookie, never readable by page JavaScript |
| What reaches the browser | A Server Component hands Client Components a narrowed view, not the API record. See below |
| Rate limiting | Redis token bucket at the gateway, per client IP |
| Error hygiene | Denials expose no resource detail; the catch-all logs server-side and returns a generic message |
| Log injection | The request path is reduced to the RFC 3986 path alphabet before being logged |
| Static analysis | CodeQL on Java and TypeScript; Trivy over dependencies, Dockerfiles and the base image |
| Signing key | `JWT_SECRET` is required from the environment at runtime. No signing key is committed or used as a fallback: both services refuse to start without it, and reject a key shorter than 256 bits |
| Repeated money movement | Deposits, withdrawals and transfers require an `Idempotency-Key`, recorded under a unique constraint with a fingerprint of the caller and the request. A repeat returns the original result; a concurrent duplicate executes once |
| Concurrent balance changes | The account row is read `SELECT ... FOR UPDATE` on every path that changes it, so two debits serialise and the second is checked against what the first left |

### Data minimization at the Server/Client boundary

React Server Components make this a real boundary rather than a stylistic one.
Anything a Server Component passes to a Client Component is serialised into the
RSC payload that ships inside the HTML, and again into the Flight response on a
client-side navigation. It is readable in view-source whether or not it is ever
rendered.

The money forms were a Client Component taking `Account[]`, so the raw
`accountNumber` was published on every visit to the page even though every
label on screen was masked. Masking inside the component would have been too
late. The server builds a `MoneyAccountOption` instead — the id the operation
needs, a ready-made label, the masked number, the currency and the available
balance the review step shows — and the account number stays on the server. The
id travels because the request cannot be made without one, and it is not a
secret: the gateway re-checks ownership on every call.

The same rule decides what the payee form gets, which is nothing. It is handed
no beneficiary data at all; the account number is typed, submitted, and the
fields are cleared, and what comes back to confirm the save is already masked.

The same question applies to text the backend writes and the customer reads
later. A transfer stores a description on each leg, and that description used to
name the other side by its internal account id — `Monthly saving → account 71`.
A primary key is not a customer-facing identifier, and on the credit leg it is a
primary key belonging to whichever account sent the money. Both legs now carry
the last four digits, the same way every screen in the console names an account,
and a unit test asserts that neither the internal id nor the full number can
reach the description.

Three tests guard it, against the three surfaces a browser actually sees: the
delivered HTML, the RSC/Flight response captured during a real client-side
navigation, and the rendered DOM including `aria-label`, `title` and `data-*`
attributes. They read the account numbers back from the API for the signed-in
customer rather than using a fixture, and they never print the value when they
fail. A unit test covers the same property on the serialised view model, so the
cheap half runs in CI.

### On not hashing the identity number

There is deliberately no digest of the full Social Security number alongside the
four digits. A Social Security number has fewer than a billion possible values,
so an unkeyed hash of one is recoverable by exhaustive search in seconds — it
would look like a protection without being one. A keyed HMAC with a secret held
outside the database would be defensible, but only in aid of something that
actually needs to match identities across records, and nothing here does.

Nothing in this system verifies an identity. There is no verification provider
behind it, so the stored status is `SUBMITTED`, the enum has no other value, and
no screen reports an identity as verified. Passing a format check is not
verification, and saying otherwise would be the product making a claim about
itself that is not true.

## Next hardening candidates

Recorded rather than implemented. Each is a separate decision.

### 1. No Kafka dead-letter handling — medium

**Evidence.** No `DefaultErrorHandler`, `RetryTopic` or dead-letter configuration
anywhere in the codebase. A poison message hits Spring Kafka's default
retry-then-log behaviour and the event is dropped silently.

**Impact.** Statistics, notifications and fraud scoring consume these topics. A
dropped `transaction-events` message means fraud scoring never sees a transaction,
with nothing to indicate it was missed.

**Smallest safe fix.** A `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer`
per consumer factory, plus a dead-letter topic per consumer group.

### 2. No per-account login throttling — medium

**Current policy.** `RegisterRequest` requires at least 8 characters with an
uppercase letter, a lowercase letter and a number (`@Size(min = 8, max = 100)`
plus a `@Pattern`), and the console shows the same rule as a live checklist.
Passwords are stored with BCrypt. The earlier six-character minimum, under
which `Password1` was acceptable, is gone.

**What is still missing.** There is no breach-corpus check, and no per-account
lockout or attempt throttling on `/api/auth/login` — the only limit is the
gateway's per-IP rate limit, which does not stop a distributed attempt against
one account.

**Smallest safe fix.** Per-account attempt throttling with a backoff, and a
check against a known-breached password list at registration.

### 3. Two-factor is opt-in — low

**Evidence.** `User.twoFactorEnabled` defaults to false; the gate at
`UserServiceImpl` applies only when the flag is set.

**Impact.** The TOTP implementation is correct and enforced once enabled, but no
account has it on by default, so the protection is advisory.

**Smallest safe fix.** Require 2FA for `EMPLOYEE` and `ADMIN` roles, where the
blast radius of a compromised account is largest.
