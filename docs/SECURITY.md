# Security model

How a request is authenticated, how it is authorised, and what is not covered.

This is a portfolio project. It handles no real money and holds no real customer
data, and it makes no regulatory or certification claims.


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
configuration (see GHSA-rhhx-6j8h-8cvw under Dependency advisories).

Because `/actuator` is one of those public paths, and because the gateway is the
only service whose actuator sits on the public port, whatever the gateway
publishes there it publishes to unauthenticated callers. It exposes `health`,
`info` and `prometheus` and nothing else. The `gateway` endpoint used to be
exposed alongside them: `/actuator/gateway/routes` answered anyone with every
route id, predicate and `lb://` target in the platform — a map of the internal
topology, and a surface whose `POST` sub-paths can refresh routes. It is gone,
and `GatewayRouteExposureTest` fails the build if it returns or if `health` and
`prometheus` stop being published, since the Compose readiness probe and the
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

That rule was applied unevenly at first, and it took three passes to finish.

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

A third pass covered `integration-service`, the last service without
`common-security` on its classpath, and the two-factor endpoints in
`user-service`.

`integration-service` exposes the outward rails. Nothing checked that the
`fromAccountId` on a wire, ACH or SWIFT request belonged to the caller, so any
signed-in customer could send money out of an account that was not theirs by
changing one number in a request body; and nothing checked who read a transfer
back, so a guessed reference returned another customer's beneficiary name,
IBAN, routing number and amount. It now resolves the owning user from
`account-service` and authorises against it before the transfer is persisted,
so a refused request writes no row and publishes no event.

Sending and reading use **different** rules there, which was the first place this
platform deliberately departed from `requireOwnerOrStaff` for an account
operation. Reading a transfer is owner-or-staff, matching transaction history.
Initiating one is owner-only, including for employees and admins: an external
transfer is the single action that moves money out of the bank along a rail
with no in-product reversal, and nothing in this project establishes that staff
may start one for a customer. Where the policy was silent about an irreversible
outward payment, the narrow reading was taken rather than inherited by accident
from a shared helper. Widening it is a product decision, and would want a
staff-initiated-transfer audit trail first.

The two-factor endpoints — enrol, confirm, remove — took a `userId` request
parameter and used it unchecked, so any customer could enrol an authenticator
against another account or strip one off. These manage a credential, so the
rule is `requireSelf` rather than `requireOwnerOrStaff`: an employee may review
a customer's KYC documents because a workflow needs it, but no role needs to
manage someone else's second factor, and a staff account that could remove one
could take it off before signing in as that customer.

A fourth pass, an audit of every controller, took the same reading for all money
leaving an account. Withdrawals, transfers out and payments had been
owner-or-staff on the source account, so an employee could send a customer's
money to an account of their choosing; loan repayments and payoffs, cash
advances and card payments were owner-or-staff too, so an employee could repay a
customer's debt from the customer's own account. All of these are now the
account holder's alone. The same audit found staff able to decide about
themselves — approve their own credit application, set their own identity
status or credit score, change their own account's status or overdraft limit —
and those are refused by `requireStaffActingForAnother`. A member of staff who is
also a customer is a customer for their own records.

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
| What travels on Kafka | Events carry identifiers and, where a message names something to a customer, a masked form. Never a full account number, PAN, SSN, password, token or TOTP secret. `ACCOUNT_CREATED` used to carry the full account number for no consumer; a contract test now asserts no event declares such a field |
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

## Dependency advisories

The scanner reports advisories against the packages this platform depends on.
A package containing vulnerable code is not the same as an exploitable path
through this codebase, and neither fact excuses the other, so each is audited
for reachability and recorded with the evidence.

The springdoc advisory is patched: the platform now resolves springdoc-openapi
2.9.1. The Kafka and Boot entries are not patched. The platform still resolves
the affected `spring-kafka` 3.2.10 and Spring Boot 3.3.13, and their fixes are
in release trains that need a coordinated Spring Boot, Spring Framework and
Spring Cloud upgrade — recorded at the end as deferred platform maintenance.

### GHSA-rhhx-6j8h-8cvw — springdoc-openapi — PATCHED AND NOT EXPOSED IN NORMAL RUNTIME

**What it is.** springdoc-openapi 2.0.0 up to (not including) 2.9.1 caches the
rendered OpenAPI document per `Accept-Language` value without bound. Every
request to `/v3/api-docs` in a new locale adds an entry, so a caller can grow
the cache until the heap is exhausted.

**Reachability.** Reachable before #98. The gateway carried
`springdoc-openapi-starter-webflux-ui` 2.6.0, and its own `/v3/api-docs` and
Swagger UI were enabled. They are served by the gateway itself, outside every
route, so the JWT filter never sees them. The filter also listed `/v3/api-docs`
and `/swagger-ui` as public paths. An unauthenticated caller on port 8080 could
therefore drive the cache. Each business service carried the webmvc starter at
the same version, reachable only through `docker-compose.dev-ports.yml`.

**Control.** Two layers. #97 moved springdoc-openapi to 2.9.1, which is
outside the affected range. Before that, #98 switched the feature off, and it
stays off as defence in depth:
- The gateway sets `springdoc.api-docs.enabled` and
  `springdoc.swagger-ui.enabled` to `false`.
- Every business service defaults both to `${SPRINGDOC_ENABLED:false}`.
- `/v3/api-docs` and `/swagger-ui` are no longer public paths in
  `JwtAuthenticationFilter`.
- Only the development override (`docker-compose.dev-ports.yml`, which
  publishes the services on local ports and is documented as unfit for a
  shared host) sets `SPRINGDOC_ENABLED=true`. Nothing in the running product
  uses the documents.

**Evidence.**
- `GatewayRouteExposureTest.apiDocsAndSwaggerUiAreOff`: the gateway
  configuration turns both off.
- `ServiceApiDocsDefaultTest`: every service that configures springdoc
  defaults it to off.
- `ApiDocsExposureTest`: the filter refuses `/v3/api-docs`,
  `/v3/api-docs/swagger-config`, `/swagger-ui/index.html` and
  `/swagger-ui.html` without a token, with an attacker-style
  `Accept-Language`.
- All six fail against the previous code.
- The PowerShell suite requests the three document paths from the running
  gateway, each with a fresh `Accept-Language`, and requires 401 or 404.

**Residual risk.** None known for this advisory: the resolved version is
patched and the endpoint is not served in the normal runtime. The development
override still turns the docs on for local ports only. After the upgrade, the
running gateway still answers 404 on every docs path, and the exposure tests
still pass.

### CVE-2026-41731 — spring-kafka — high — MITIGATED IN CONFIGURATION

**What it is.** Arbitrary code execution through insecure deserialization of
crafted Kafka header values.

**Reachability.** Reachable before this change. Spring maps Kafka headers
separately from the payload, so the payload hardening — type headers off,
routing on an `eventType` field, trusted packages narrowed to the contract
package — constrained none of it. With no header mapper configured, the
framework default reads a `spring_json_header_types` header naming a Java
class and constructs it while building the message for the listener. A
producer controls those bytes.

**Control.** `SimpleKafkaHeaderMapper`, installed platform-wide through the
`RecordMessageConverter` bean that Spring Boot hands to every listener
container factory. Headers arrive as raw bytes; no type name is honoured and
no object is constructed from a producer-supplied header. The capability is
removed rather than policed — an allowlist would still perform reflective
construction and would still depend on the framework parsing the header
correctly, which is the thing the advisory says it does not.

Northbank needs no typed header objects. The headers that matter are a
correlation id, trace context, event identity and dead-letter metadata, all
strings or bytes.

**Evidence.** `KafkaHeaderSafetyIT` asserts the effective listener
configuration: that the converter Boot hands the factory carries the raw
mapper, that a record naming `java.net.URL`, `java.util.Date` or
`java.math.BigDecimal` in `spring_json_header_types` produces no such object,
that ordinary events and correlation headers still arrive, and that
dead-lettering and payload-deserialization failure handling are unaffected.

**Residual risk.** The package is still the affected version. A different
reachable path through header handling in the same library would not be
covered by this control.

### CVE-2026-41726 — spring-kafka — medium — NOT REACHABLE, FEATURE NOT USED

Denial of service through unbounded heap growth in `DelegatingDeserializer`.

Northbank does not use it. No `DelegatingDeserializer`, no
`spring.kafka.serialization.selector` configuration and no selector-header
handling exists anywhere in the repository. Every consumer uses
`ErrorHandlingDeserializer` delegating to `JsonDeserializer`, declared
explicitly in each service's configuration.

No code was written to work around a feature this platform does not use.

### CVE-2026-41727 — spring-kafka — medium — NOT REACHABLE, FEATURE NOT USED

Retry-sequence manipulation through improper validation of retry-topic header
values.

Northbank does not use retry topics. There is no `@RetryableTopic`, no
`RetryTopicConfiguration` and no retry-topic infrastructure. Recovery is a
`DefaultErrorHandler` with bounded backoff and a
`DeadLetterPublishingRecoverer`, which is a deliberate design choice and not a
workaround: it keeps in-order retry semantics rather than re-queueing through
side topics.

### CVE-2026-41001 — spring-boot — medium — NOT REACHABLE, FEATURE NOT USED

A local attacker can manipulate an embedded Artemis data directory through a
predictable path.

Northbank does not use Artemis. It is not on the dependency tree, there is no
`spring-boot-starter-artemis`, and no ActiveMQ or Artemis configuration exists.
The finding is attributed to `spring-boot-autoconfigure`, which is present for
every other reason a Spring Boot application needs it.

### CVE-2026-22733 — spring-boot-actuator — high — NOT REACHABLE, FEATURE NOT USED

Authentication can be bypassed on the actuator's Cloud Foundry endpoints
(`/cloudfoundryapplication/**`). Fixed in Spring Boot 3.5.12 and 4.0.4; the
platform resolves 3.3.13, so the package is still the affected version.

Those endpoints exist only on Cloud Foundry. Their servlet and reactive
auto-configurations are conditional on `CloudPlatform.CLOUD_FOUNDRY`, which
Spring Boot detects from the `VCAP_APPLICATION` or `VCAP_SERVICES` environment
variables. Northbank runs on Docker Compose, its Terraform targets ECS, and
nothing sets those variables or `spring.main.cloud-platform`. On the running
gateway, `/cloudfoundryapplication` and `/cloudfoundryapplication/health`
answer 404 while `/actuator/health` answers 200.

**Residual risk.** Deploying to Cloud Foundry would activate the endpoints. The
fix is in the release-train upgrade below.

### Deferred: platform modernization

The fixes for the advisories above are in `spring-kafka` 3.3.16 / 4.0.6 and
Spring Boot 3.5.15 / 4.0.7 (3.5.12 / 4.0.4 for CVE-2026-22733). This platform runs Spring Boot 3.3.13 with Spring
Cloud 2023.0.6, which resolve `spring-kafka` 3.2.10 — still in the affected
range, since the patch-level upgrade in #97 does not reach the fixed lines. Those
Kafka versions target a later Spring Framework
generation — so this is a coordinated release-train upgrade across Boot,
Framework, Cloud and Data, not a single dependency bump. Overriding one
component into an unsupported combination to quiet a scanner would be a worse
outcome than the finding.

It is tracked as its own piece of work, to be done against a full repository
test gate rather than folded into a feature change.

## Next hardening candidates

Recorded rather than implemented. Each is a separate decision.

### 1. No breached-password check — low

**Current policy.** `RegisterRequest` requires at least 8 characters with an
uppercase letter, a lowercase letter and a number (`@Size(min = 8, max = 100)`
plus a `@Pattern`), and the console shows the same rule as a live checklist.
Passwords are stored with BCrypt. Repeated failures against one account are
throttled per account in Redis, as described in
[Guessing one account's password](#guessing-one-accounts-password), on top of
the gateway's per-IP rate limit.

**What is still missing.** Nothing checks a new password against a corpus of
known-breached passwords, so a password that meets the composition rule but is
widely leaked is accepted.

**Smallest safe fix.** A k-anonymity range lookup against a breached-password
list at registration and password change.

### 2. Two-factor is opt-in — low

**Evidence.** `User.twoFactorEnabled` defaults to false; the gate at
`UserServiceImpl` applies only when the flag is set.

**Impact.** The TOTP implementation is correct and enforced once enabled, but no
account has it on by default, so the protection is advisory.

**Smallest safe fix.** Require 2FA for `EMPLOYEE` and `ADMIN` roles, where the
blast radius of a compromised account is largest. Not done here because
enrolment requires an authenticated caller and is self-only: demanding a second
factor before any staff token is issued would leave a staff account that has
never enrolled with no way to enrol. Solving it properly means a scoped
enrolment token and a separate first-sign-in flow, which is an authentication
design of its own rather than a check to add.
