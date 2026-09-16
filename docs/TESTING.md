# Testing

187 automated tests run in CI: 104 backend, 70 frontend unit/component and 13
offline end-to-end. A further 9 live-stack Playwright scenarios run on demand and
are not counted in the CI total.

## Suites

| Layer | Tooling | Scope | Result |
|---|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ | `AccountServiceImpl` balance and overdraft rules | 21 passing |
| Unit | JUnit 5, Mockito, AssertJ | `LoanServiceImpl` amortization, repayment, payoff | 27 passing |
| Unit | JUnit 5, Mockito, AssertJ | `CreditCardMasking` PAN masking and `last4` derivation | 12 passing |
| Unit | JUnit 5, Mockito, AssertJ | `LoginTwoFactor` TOTP gate at sign-in | 6 passing |
| Unit | JUnit 5, AssertJ | `RequestIdPropagation` — id minted, preserved, sanitised, forwarded | 18 passing |
| Unit | JUnit 5, Resilience4j | `AccountServiceCircuitBreaker` — breaker policy and status mapping | 9 passing |
| Web slice | JUnit 5, MockMvc | `ApiErrorContract` — invalid input returns 4xx, errors expose no internals | 5 passing |
| Integration | Testcontainers, PostgreSQL 16 | `account-service` migrations and persistence | 6 passing |
| Unit | Vitest, React Testing Library | Formatting, masking, JWT decode, validation, role nav, API errors, UI components | 70 passing |
| End-to-end | Playwright (offline) | Route protection, session cookie, form validation, responsive layout | 13 passing, in CI |
| End-to-end | Playwright (live) | Sign-in, accounts, transfer, loan schedule, card masking, staff access, sign-out | 9, on demand |
| End-to-end | PowerShell (`e2e-tests.ps1`) | Banking flows against the running stack | On demand |

## Commands

```bash
mvn -B --no-transfer-progress clean verify   # backend: 98 unit + 6 integration = 104
cd frontend && npm run test                  # frontend: 70 unit/component
cd frontend && npm run test:e2e              # frontend: 13 offline end-to-end
```

Unit tests run in the `test` phase. Integration tests are named `*IT` and bound to
Failsafe in the `verify` phase, so `mvn test` stays Docker-free while `mvn verify`
runs both.

## What the backend tests assert

Unit tests assert on the entity handed to the repository — the state the service
commits — rather than on mapper output. Money is compared with
`isEqualByComparingTo`, so a difference in `BigDecimal` scale cannot pass for a
difference in value.

**Accounts** — credit and debit arithmetic; the boundary where a debit drains the
balance to exactly zero without tripping overdraft; insufficient-funds rejection,
including head-room already consumed by an existing overdraft; the overdraft path
(deficit moved to `overdraftBalance`, `OVERDRAWN` status, `$35.00` fee, event
published); full and partial overdraft repayment and the transition back to
`ACTIVE`; `FROZEN` and `CLOSED` accounts rejecting both directions; closed accounts
refusing to reopen; and the audit row plus event being written on success and not
on a rejected debit.

**Loans** — the amortised monthly payment for $10,000 at 6.00% APR over 12 months,
checked against the external reference value of $860.66 rather than against the
implementation's own formula; a 12-row schedule whose principal portions sum
exactly to the amount borrowed and whose final balance is zero; zero-interest loans
splitting evenly; interest-before-principal allocation; `PAID` versus `PARTIAL`
instalment marking; overpayment capped at the payoff figure; loan closure on the
final instalment; and early payoff settling balance plus accrued interest.

**Cards** — masking asserted on serialised JSON as well as on the mapped object, so
a full PAN cannot reach a response body.

**Resilience** — the breaker opens on sustained downstream failure, stays closed
for business 4xx, maps a wrapped 422 back to 422, and attempts a debit exactly once.

## Integration test

`AccountRepositoryIT` runs `@DataJpaTest` against a real PostgreSQL 16 container:
Flyway migrations apply to an empty database, the JPA mappings agree with the
migrated schema (`ddl-auto: validate` fails the test on drift), `DECIMAL(19,2)`
survives a round trip without losing scale, negative balance and overdraft
positions persist correctly, and the unique constraint on `account_number` is
enforced by the database.

It requires a running Docker daemon. `mvn test` skips it.

## Frontend tests

Money formatting and the card/account masking that keeps a full PAN off the screen;
JWT decoding, including rejecting a token with no `userId`; every form schema,
including the backend's "not the same account" rule for transfers; role-based
navigation for all three roles; HTTP-status-to-message mapping; and the
accessibility contract of the form primitives — label binding, `aria-invalid`,
`aria-describedby`, and a submit button that disables while pending.

They do not assert on markup structure or class names, so a restyle does not break
them.

## End-to-end split

**Offline (13 tests, in CI).** Drives a production build of the console with the
gateway pointed at a dead port. Covers route protection, expired and malformed
sessions, form validation, responsive layout, and two architecture guarantees:
the session cookie is httpOnly, and the token never appears in the HTML sent to
the browser. No backend required.

**Live (9 tests, on demand).** Requires all 13 services plus a seeded customer.
Covers sign-in to a dashboard showing real balances, account and transaction
history, transfer review and confirmation, loan amortization, card masking,
staff-route denial for a customer, sign-out, and a phone viewport.

```bash
# offline — no backend required
cd frontend && npm run test:e2e

# live — requires the full stack
docker compose up -d && ./scripts/seed-demo.sh
cd frontend && E2E_USERNAME=<printed> E2E_PASSWORD=<printed> npm run test:e2e:live
```

## Coverage boundary

Coverage is deep on the services holding the most consequential arithmetic —
balances and amortization — plus card masking, the 2FA gate, the shared API error
contract, request correlation and the circuit-breaker policy. The remaining 9
services have no service-layer tests, there is one MockMvc slice and no Spring
Security slice tests, and only `account-service` has an integration test.
