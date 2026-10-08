# Security model

How a request is authenticated, how it is authorised, and how the controls are enforced.

Northbank runs on synthetic data. It handles no real money and holds no real
customer data, and it makes no regulatory or certification claims.


## Guessing one account's password

The gateway rate-limits by IP. That stops one noisy source and does not see a
distributed attempt on a single username: many quiet sources working through
one account look like ordinary traffic to it.

Failed sign-ins are therefore also counted per account, in Redis — shared
across replicas, with a TTL, because an in-process counter resets on restart
and is defeated by spreading attempts across instances.

- **The key is a SHA-256 of the submitted username**, never the username.
  Anyone reading a shared Redis keyspace would otherwise see a list of the
  accounts under attack. The digest is of what was typed, so an attempt
  against a name that does not exist is still counted and nothing here
  distinguishes registered names from unregistered ones.
- **The increment and its expiry are one script**, so a crash between them
  cannot leave a key with no TTL — an account locked out permanently by an
  infrastructure hiccup.
- **The check reads the count and the remaining TTL together**, so the two
  describe the same moment. Read separately, the key can expire in between and
  the caller is told to wait a full window for a block that has already
  lifted.
- **The refusal says nothing about the account.** 429 with `Retry-After`, and
  wording about the attempts rather than the account: "this account is locked"
  would confirm the username exists to someone guessing names.

The counting follows the credential rather than the request. A correct
password with the second factor still outstanding is neither a failure nor a
success — it is not counted, and it does not clear the counter, because
counting it would lock a two-factor customer out for doing what the form asked
and clearing it would let a guesser reset the count by stopping one step
short. A wrong code is counted: it is the second half of a guess.

**Sign-in fails closed if Redis is unavailable** — 503, and nobody signs in.
That is a real availability cost, taken deliberately: the alternative is
removing the only limit on guessing one account's password at exactly the
moment the platform cannot observe it.

**It fails closed on corrupted state too**, which is the less obvious half. A
counter that is not a number, or one that is negative, is a value this service
did not write, and the only safe answer to "how many failures has this account
had?" is to stop rather than to guess. Answering zero — which is what the
first version did, reasoning that bad data should not cause a permanent
lockout — made the throttle removable by anyone who could corrupt the key. The
reasoning was wrong in a way worth naming: the alternative to a permanent
lockout is not "let them through", it is "fail closed until the key expires",
which the TTL guarantees anyway.

The expiry is treated differently from the count on purpose. It decides only
what the caller is told to wait, so an unreadable one degrades rather than
refusing — but upwards, to the full window, never to none.

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

Public gateway routes — `/api/auth/register`, `/api/auth/login` and `/actuator`
— establish no identity. The filter still runs on them to remove any `X-User-*`
headers the client wrote, so a service behind a public path never takes them as
real. Protected routes in the services reject identity-less requests, so a public
path is not a way in. API docs and Swagger UI are disabled in the normal runtime;
service docs are available only through the local `docker-compose.dev-ports.yml`
configuration (see Dependency and security scanning).

Because `/actuator` is one of those public paths, and because the gateway is the
only service whose actuator sits on the public port, whatever the gateway
publishes there it publishes to unauthenticated callers. It exposes `health`,
`info` and `prometheus` and nothing else. The `gateway` actuator endpoint is not
exposed: `/actuator/gateway/routes` would answer anyone with every route id,
predicate and `lb://` target in the platform — a map of the internal topology —
and its `POST` sub-paths can refresh routes. `GatewayRouteExposureTest` fails
the build if it is exposed or if `health` and `prometheus` stop being published, since the Compose readiness probe and the
Prometheus scrape depend on them.

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
| `requireStaffActingForAnother` | employee or admin, and never when the subject is the caller |

### What each principal may reach

| | Customer (own) | Customer (another's) | Employee | Admin |
|---|---|---|---|---|
| Accounts, transactions, profile, KYC, per-user statistics | yes | **no** | yes | yes |
| Payees, payments, notifications, applications | yes | **no** | yes | yes |
| Loans: detail, schedule, repayments, payoff quote | yes | **no** | yes | yes |
| Loans: receive, repay, early payoff | own only | **no** | **no** | **no** |
| Cards: detail, transactions, statements | yes | **no** | yes | yes |
| Cards: cash advance, payment | own only | **no** | **no** | **no** |
| Cards: freeze and unfreeze (`ACTIVE` ↔ `CUSTOMER_FROZEN` only) | own only | **no** | yes, plus bank states | yes, plus bank states |
| Cards: purchase (simulated merchant), statement generation | **no** | **no** | yes | yes |
| Open an account, submit an application | for self | **no** | for anyone | for anyone |
| Accept or decline a credit offer | own only | **no** | **no** | **no** |
| Withdraw, transfer out, pay from an account | from own accounts | **no** | **no** | **no** |
| External transfer (wire / ACH / SWIFT): initiate | from own accounts | **no** | **no** | **no** |
| External transfer: read by reference | yes | **no** | yes | yes |
| Second factor: enrol, confirm, remove | own only | **no** | **no** | **no** |
| Cancel an application, remove a payee | own only | **no** | yes | yes |
| Account status, overdraft limit | **no** | **no** | yes, not their own | yes, not their own |
| Platform and daily statistics | **no** | **no** | yes | yes |
| KYC document review and identity decision, credit-score update | **no** | **no** | yes, not their own | yes, not their own |
| Application queue by status | **no** | **no** | yes | yes |
| Manual decision on a referred application | **no** | **no** | yes, not their own | yes, not their own |
| Fraud alerts: list, read, review | **no** | **no** | yes | yes |

An id in a path or a `userId` in a request body is caller input. It is checked
against the identity the gateway established, never trusted as proof of ownership.

Every service resolves the owner from stored state and authorises against it,
and each has a regression suite that fails if the guard is removed.

Money leaving an account is the account holder's alone. Withdrawals, transfers
out, payments, loan repayments and payoffs, cash advances and card payments all
require the account's owner; staff can read and deposit, but cannot send a
customer's money anywhere or repay a customer's debt from the customer's
account.

External transfers use different rules for sending and reading. Reading a
transfer is owner-or-staff, matching transaction history. Initiating one is
owner-only, including for employees and admins: an external transfer is the
single action that moves money out of the bank along a rail with no in-product
reversal, and nothing in this project establishes that staff may start one for
a customer. Ownership is checked against `account-service` before the transfer
is persisted, so a refused request writes no row and publishes no event.

The two-factor endpoints (enrol, confirm, remove) are `requireSelf`. They manage
a credential: an employee may review a customer's KYC documents because a
workflow needs it, but no role needs to manage someone else's second factor, and
a staff account that could remove one could take it off before signing in as
that customer.

Staff never decide about themselves. Approving their own credit application,
setting their own identity status or credit score, and changing their own
account's status or overdraft limit are refused by
`requireStaffActingForAnother`. A member of staff who is also a customer is a
customer for their own records.

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

### Work with no caller

A scheduled payment runs from a timer, so there is no request identity to forward.
`payment-service` reads the paying account's owner through
`/internal/accounts/{id}` and makes the transfer inside `CallerContext.runAs` as that
customer; `CallerIdentityFeignInterceptor` forwards a `runAs` identity only when there
is no inbound request, and an inbound request's identity always wins. `runAs` is the
only way to set one, and it is only ever set from stored state. `transaction-service`
then applies the same ownership check it would to the customer's own request.

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
| Money leaving an account | Only the account's owner can take money out of it: withdrawals, transfers out, payments, loan repayments and payoffs, and card payments are `requireSelf` against the stored owner. Staff can read, deposit and cancel, but an employee cannot move a customer's money to an account of their choosing |
| Decisions about oneself | Approving a referred application, reviewing KYC documents, setting an identity status or credit score, and changing an account's status or overdraft limit are refused when the subject is the member of staff (`requireStaffActingForAnother`). Staff opening their own account cannot set its overdraft limit |
| Reviewer attribution | A KYC review is recorded under the signed-in reviewer; any `reviewedBy` in the request body is ignored |
| Username lookup | A customer is refused before the lookup for any name but their own, so a 404 cannot reveal which usernames exist |
| Second-factor management | Enrolling, confirming and removing an authenticator are self-only — `AccessGuard.requireSelf`, not owner-or-staff. No role can take another account's second factor off |
| Outward transfer rails | A wire, ACH or SWIFT transfer may only be initiated from an account the caller owns, resolved from `account-service` rather than read from the request. Staff are not exempt |
| What travels on Kafka | Events carry identifiers and, where a message names something to a customer, a masked form. Never a full account number, PAN, SSN, password, token or TOTP secret. A contract test asserts that no event declares such a field |
| Kafka headers | Mapped as raw bytes by `SimpleKafkaHeaderMapper`, so no Java type named by a producer is ever constructed. Header mapping is a separate trust boundary from payload deserialization, and the framework default reconstructs types from a `spring_json_header_types` header the producer controls |
| Kafka deserialization | Type headers are off, and every service's trusted-package list is `com.bankingplatform.common.events` — the contract package alone. It was `*` in four services and `com.bankingplatform.*` in four more. A record names its type in an `eventType` field rather than a Java class the consumer would instantiate |
| Management endpoints | `health`, `info` and `prometheus` only, on every service including the gateway, whose actuator is the one reachable without a token |
| Error hygiene | Denials expose no resource detail; the catch-all logs server-side and returns a generic message |
| Log injection | The request path is reduced to the RFC 3986 path alphabet before being logged |
| Static analysis | CodeQL on Java and TypeScript; Trivy over dependencies, Dockerfiles and the base image |
| Signing key | `JWT_SECRET` is required from the environment at runtime. No signing key is committed or used as a fallback: both services refuse to start without it, and reject a key shorter than 256 bits |
| Repeated money movement | Deposits, withdrawals, transfers, payments, loan repayments and payoffs, and card payments require an `Idempotency-Key`, recorded under a unique constraint with a fingerprint of the caller and the request. A repeat returns the original result; a concurrent duplicate executes once |
| Amount and currency as confirmed | A loan payoff takes only the figure the customer confirmed and is refused with the new figure if the balance moved since the quote. Every money request is in the account's own currency: one naming another is refused before any balance call, and a loan or card can only be funded from an account in its currency. Nothing converts |
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
later. A transfer stores a description on each leg, and each leg names the other
side by the last four digits of its account number, the same way every screen in
the console names an account. It never uses the internal account id: a primary
key is not a customer-facing identifier, and on the credit leg it would belong to
whichever account sent the money. A unit test asserts that neither the internal id nor the full number can
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

Nothing automated in this system verifies an identity. There is no verification
provider behind it, so the identity-details record's status is `SUBMITTED` and its
enum has no other value. Passing a format check is not verification, and saying
otherwise would be the product making a claim about itself that is not true. What
does exist is a person's decision: a member of staff reviews the submitted
documents and sets the customer's KYC status to `APPROVED` or `REJECTED`. That is
recorded under the reviewer, refused for their own identity, and it is what
underwriting reads. An identity can only be approved once at least one of the
customer's documents has been, the documents are the customer's own to submit, and
a reviewer cannot approve credit for a customer whose identity is not approved.

## A corrupted fraud counter is not evidence

The velocity and failed-payment rules count in Redis, and those counters feed a
risk score that can raise an alert and freeze a live customer's account.

A counter holding something this service did not write has two readings and no
safe one. Read low, the velocity rule keeps running with the control
effectively switched off for that account — silent, indefinite, and
indistinguishable from a quiet customer. Read high, the platform records a
claim that this customer was moving money too fast, manufactured out of a
storage fault.

Neither is a fact about the customer. It is a fact about the store, so it is
reported as one: the event processing fails with a controlled exception, which
rolls back the processed-event claim and reaches the retry and dead-letter path
that already exists for operational problems. The record is preserved and an
operator sees it.

A missing counter is deliberately not treated as corruption. The counter
carries its window's TTL, so its absence is the window having closed, and
reading it as the first of a new window is what the increment path would have
produced anyway.

## Dependency and security scanning

CI runs CodeQL over the Java and TypeScript code, and Trivy over the dependencies,
Dockerfiles, the runtime base image and the Terraform. Each finding is assessed
against the versions the build actually resolves, the runtime configuration and
the features the application exposes; a package that contains vulnerable code is
not the same as a reachable path through this codebase.

A vulnerable capability that is part of the application surface is patched or
switched off before release. springdoc-openapi is on the patched 2.9.1 line, and
the API docs and Swagger UI stay disabled in the normal runtime, guarded by
regression tests at the gateway and in every service. Some findings remain
against framework packages on the Spring Boot 3.3 line; for each, the affected
feature is either not used by Northbank or constrained in configuration. Kafka
listeners, for example, map headers as raw bytes and never construct a type a
producer names.
