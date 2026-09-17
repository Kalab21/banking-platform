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
| Open an account | for self | **no** | for anyone | for anyone |
| Move money | from own accounts | **no** | — | — |
| Freeze account, overdraft limit | **no** | **no** | yes | yes |
| Platform and daily statistics | **no** | **no** | yes | yes |
| KYC review, credit-score update | **no** | **no** | yes | yes |

An id in a path or a `userId` in a request body is caller input. It is checked
against the identity the gateway established, never trusted as proof of ownership.

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
| Browser session | JWT in an httpOnly, SameSite=Lax cookie, never readable by page JavaScript |
| Rate limiting | Redis token bucket at the gateway, per client IP |
| Error hygiene | Denials expose no resource detail; the catch-all logs server-side and returns a generic message |
| Log injection | The request path is reduced to the RFC 3986 path alphabet before being logged |
| Static analysis | CodeQL on Java and TypeScript; Trivy over dependencies, Dockerfiles and the base image |

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

### 2. Weak password policy — medium

**Evidence.** `RegisterRequest` constrains the password with `@Size(min = 6)` and
no complexity or breach check. `Password1` is accepted.

**Impact.** BCrypt makes offline cracking expensive, but a six-character minimum
makes online guessing and credential stuffing cheap. There is no lockout or
attempt throttling on `/api/auth/login` beyond the gateway's per-IP rate limit.

**Smallest safe fix.** Raise the minimum to 12, and add per-account attempt
throttling on login.

### 3. Development JWT secret is public — medium

**Evidence.** `jwt.secret: ${JWT_SECRET:banking-platform-secret-key-change-in-production}`
in `api-gateway` and `user-service`.

**Impact.** The fallback is committed and therefore public. Anyone running the
default configuration has a known signing key, so tokens can be forged for any
user and role — which would defeat the entire authorization model above. It is
clearly labelled and environment-overridable, and AWS deployments use Secrets
Manager, but the default remains usable.

**Smallest safe fix.** Remove the fallback so the services refuse to start without
`JWT_SECRET`, and generate one in the local `.env`.

### 4. Two-factor is opt-in — low

**Evidence.** `User.twoFactorEnabled` defaults to false; the gate at
`UserServiceImpl` applies only when the flag is set.

**Impact.** The TOTP implementation is correct and enforced once enabled, but no
account has it on by default, so the protection is advisory.

**Smallest safe fix.** Require 2FA for `EMPLOYEE` and `ADMIN` roles, where the
blast radius of a compromised account is largest.

### 5. No idempotency or concurrency control on money movement — high, documented

**Evidence.** `updateBalance` is a read-modify-write with no `@Version` or
`SELECT ... FOR UPDATE`; no endpoint accepts an idempotency key.

**Impact.** Concurrent debits on one account can interleave and lose an update, and
a retried transfer applies twice. This is why the circuit breaker on
`transaction-service` deliberately has no retry.

**Smallest safe fix.** `@Version` on `Account`, and an idempotency key persisted
with a unique constraint on the transfer endpoint.
