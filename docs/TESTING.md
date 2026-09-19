# Testing

808 automated tests run in CI: 526 backend (501 unit and
web-slice, 25 integration against a real PostgreSQL), 213 frontend
unit/component and 69 offline end-to-end. A further 34 live-stack
Playwright scenarios and a PowerShell full-stack suite run on demand and are not
counted in the CI total.

Counts are per test case as the runners report them — JUnit `tests` in the
surefire and failsafe XML, Vitest test cases, Playwright tests. They are not
assertion counts, which are larger and less comparable.

## Suites

| Layer | Tooling | Scope | Result |
|---|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ | `AccountServiceImpl` balance and overdraft rules | 23 |
| Unit | JUnit 5, Mockito, AssertJ | `LoanServiceImpl` amortization, repayment, payoff | 27 |
| Unit | JUnit 5, Mockito, AssertJ | `CreditCardMasking` PAN masking and `last4` derivation | 12 |
| Unit | JUnit 5, Mockito, AssertJ | `LoginTwoFactor` TOTP gate at sign-in | 6 |
| Unit | JUnit 5, AssertJ | `RequestIdPropagation` — id minted, preserved, sanitised, forwarded | 18 |
| Unit | JUnit 5, Resilience4j | `AccountServiceCircuitBreaker` — breaker policy and status mapping | 9 |
| Unit | JUnit 5, AssertJ | `LogSafe` — an untrusted value cannot end a log line and start another | 13 |
| Web slice | JUnit 5, MockMvc | `ApiErrorContract` — invalid input returns 4xx, an unmatched path 404, errors expose no internals | 6 |
| Web slice | JUnit 5, MockMvc | `RegistrationErrorResponse` — the shape a failed registration returns | 5 |
| Persistence | JUnit 5, Mockito | `OnboardingPersistence` — what onboarding stores, and what it drops | 13 |
| Validation | JUnit 5, Jakarta Validation | `OnboardingRequestValidation` — every field rule the console mirrors | 47 |
| Validation | JUnit 5, Jakarta Validation | `RegisterPasswordPolicy` — the rule the console displays is the rule the server enforces | 11 |
| Authorization | JUnit 5, AssertJ | `AccessGuard` — the rules themselves | 23 |
| Authorization | JUnit 5, AssertJ | `OwnershipMatrix` — all four principals against every resource class | 20 |
| Authorization | JUnit 5, MockMvc | `CallerIdentityExceptionHandler` — denial maps to 403, missing identity to 401 | 6 |
| Authorization | JUnit 5, MockMvc | `AccountAuthorization` — account ownership, staff-only operations, fail-closed | 19 |
| Authorization | JUnit 5, MockMvc | `BalanceMutationBoundary` — public path removed, internal path intact | 6 |
| Authorization | JUnit 5, MockMvc | `TransactionAuthorization` — money movement and transaction visibility | 13 |
| Authorization | JUnit 5, MockMvc | `UserKycAuthorization` — profile, KYC and username-lookup ownership | 12 |
| Authorization | JUnit 5, MockMvc | `StatisticsAuthorization` — per-user ownership, platform figures staff-only | 10 |
| Authorization | JUnit 5, MockMvc | `FraudAuthorization` — alerts staff-only, reviewer from caller identity | 8 |
| Authorization | JUnit 5, MockMvc | `PaymentAuthorization` — payee and payment ownership, payer account resolved | 14 |
| Authorization | JUnit 5, MockMvc | `NotificationAuthorization` — own notifications only | 7 |
| Authorization | JUnit 5, MockMvc | `ApplicationAuthorization` — own applications, staff queue and decision | 12 |
| Authorization | JUnit 5, MockMvc | `LoanAuthorization` — loan detail, schedule, repayment and payoff by owner | 15 |
| Authorization | JUnit 5, MockMvc | `CreditCardAuthorization` — card detail, transactions, statements and every write | 13 |
| Event contract | JUnit 5, Jackson | `EventContract` — envelope, versioning, type routing, unknown-type fallback, partition keys, no sensitive field on any event | 16 |
| Event contract | JUnit 5, Jackson, Mockito | `EventContractConsumption` (notification) — every notification that was dead now fires from the producer's own event, over JSON | 14 |
| Event contract | JUnit 5, Jackson, Mockito | `EventContractConsumption` (statistics) — counters attribute to the right user, submitted applications are counted | 5 |
| Event contract | JUnit 5, Jackson, Mockito | `EventContractConsumption` (fraud) — transaction and failed-payment rules receive what they read; the card listener is gone | 6 |
| Event contract | JUnit 5, Jackson, Mockito | `EventContractConsumption` (user) — card payment and loan repayment move a credit score; the missed-payment branch is gone | 6 |
| Event contract | JUnit 5, Jackson, Mockito | `ApplicationEventContract` (loan, credit-card) — an approval issues the right product on the right terms | 8 |
| Authorization | JUnit 5, MockMvc | `IntegrationAuthorization` — external transfers may only be sent from an owned account, staff included; reads are owner-or-staff | 17 |
| Authorization | JUnit 5, MockMvc | `TwoFactorAuthorization` — second-factor setup, verify and disable are self-only, and staff cannot bypass it | 10 |
| Authorization | JUnit 5, WebFlux mocks | `GatewayIdentitySpoofing` — forged identity headers are replaced | 10 |
| Configuration | JUnit 5 | `GatewayRouteExposure` — no `/internal` route, discovery locator off, actuator publishes only health/info/prometheus | 6 |
| Configuration | JUnit 5, SnakeYAML | `JwtSecretConfiguration` — no committed signing key, start-up fails without one | 7 |
| Idempotency | JUnit 5, MockMvc | `TransactionIdempotency` — key contract, replay, failure semantics, authorization order | 18 |
| Copy | JUnit 5, Mockito | `TransferDescription` — each leg names the other account masked, never by internal id | 4 |
| Copy | JUnit 5, Mockito | `NotificationCopy` — amounts in alerts are grouped dollars, card named by last four | 5 |
| Integration | Testcontainers, PostgreSQL 16 | `AccountRepositoryIT` — migrations and persistence | 6 |
| Integration | Testcontainers, PostgreSQL 16 | `AccountBalanceConcurrencyIT` — concurrent debits serialise, no lost update | 4 |
| Integration | Testcontainers, PostgreSQL 16 | `IdempotentMoneyMovementIT` — concurrent duplicates, replay, key release | 8 |
| Integration | Testcontainers, PostgreSQL 16 | `CustomerProfileMigrationIT` — the customer-profile migration | 7 |
| Unit | Vitest, React Testing Library | Formatting, masking, JWT decode, validation, role nav, API errors, UI components, password rules, phone formatting, the money-account view model, money-outcome classification, customer wording for a refused payment | 213 |
| Component | Vitest, React Testing Library | `MoveMoney` — one key per operation, double-submit, unknown outcome, receipt (counted in the 213 above) | 23 |
| Component | Vitest, React Testing Library | `AddBeneficiary` — no userId field, masked confirmation, cleared fields, empty storage (counted in the 213 above) | 9 |
| Component | Vitest, React Testing Library | `LoginForm` and `loginAction` — the second-factor challenge, and no session cookie before the code succeeds (counted in the 213 above) | 8 |
| End-to-end | Playwright (offline) | Route protection, session cookie, failure honesty, auth form validation, responsive layout down to 320px | 69, in CI |
| End-to-end | Playwright (live) | Sign-in, real balances, money movement, RSC boundary, card masking, staff denial, sign-out, phone viewport | 34, on demand |
| End-to-end | PowerShell (`e2e-tests.ps1`) | Banking flows against the running stack, including staff-only refusals and cross-customer ownership refusals | 69, on demand |

## Commands

```bash
mvn -B --no-transfer-progress clean verify   # backend: 501 unit + 25 integration = 526
cd frontend && npm run test                  # frontend: 213 unit/component
cd frontend && npm run test:e2e              # frontend: 69 offline end-to-end
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

**Log integrity** — a value a caller chose cannot end the line the service is
writing and begin one of its own. CR, LF, vertical tab, form feed and NEL are
replaced, and so are U+2028 and U+2029, which a `\p{Cntrl}` denylist would pass
through although some log viewers render them as breaks. Whitespace goes too, so
a value stays a single field. A legitimate idempotency key or request path
survives unchanged, because an entry that no longer names what it is about is
not worth writing.

**Idempotency** — a missing, malformed or over-long `Idempotency-Key` is a 400
that reaches neither the store nor the service; a replay of the same request
returns the stored response and calls nothing; an amount written `25` rather than
`25.00` is recognised as the same request, not a conflict; the same key with a
different body is a 409; a duplicate arriving mid-flight collects the original
result; and an attempt whose outcome is unknown returns 504 rather than
re-executing. The failure cases assert which way the key settles: a 4xx from
`account-service` releases it, while a timeout, a 5xx and a half-applied transfer
spend it. Authorization is asserted to run first — a caller denied the account
claims no key and leaves no record.

## Integration tests

`AccountRepositoryIT` runs `@DataJpaTest` against a real PostgreSQL 16 container:
Flyway migrations apply to an empty database, the JPA mappings agree with the
migrated schema (`ddl-auto: validate` fails the test on drift), `DECIMAL(19,2)`
survives a round trip without losing scale, negative balance and overdraft
positions persist correctly, and the unique constraint on `account_number` is
enforced by the database.

`AccountBalanceConcurrencyIT` covers the lost update. `updateBalance` reads the
balance, decides whether it is sufficient and writes a new figure; run twice at
once without a row lock, both reads see the same starting balance, both checks
pass, and the second write overwrites the first. Two simultaneous debits of 80
against a balance of 100 leave exactly one success and 20.00, never -60.00; two
debits that both fit leave 50.00 rather than 70.00 or 80.00; overdraft head-room
is measured against what the previous debit left; and ten simultaneous debits of
10 against 50 accept exactly five. A `CyclicBarrier` releases the threads
together, because without it the first request usually finishes before the second
starts and the test passes whether or not the row is locked.

The tests were checked against the defect they describe: with the lock removed,
all four fail — two 80 debits both succeed, ten of ten debits drain a balance of
50, and the lost update leaves 80.00 where 50.00 is correct.

`IdempotentMoneyMovementIT` covers the part of idempotency that only a database
can settle. It runs outside a test-managed transaction, because the guard's
correctness depends on committing its claim before the money moves — a
rolled-back test transaction would hide those commits from the second thread and
prove nothing. Two threads released together with one key produce exactly one
execution and one `COMPLETED` row; a sequential retry returns the original
result with the balance unchanged; the same key with a different amount is
refused and applies nothing; a refusal that moved no money leaves the key usable
again; an attempt whose outcome was never established is never re-executed; and
the unique constraint is asserted directly against the database rather than
inferred from the application code.

One case there is about a trap rather than a rule. `open-in-view` binds one
persistence context to a request thread, and the guard's own transactions reuse
it, so a query for an entity already in that context answers from the context
rather than from the database. A duplicate polling for the original's verdict
never saw it change, waited out its whole budget and was told to retry something
that had already finished. The store reads a constructor projection instead, and
the test binds an entity manager to the thread the way `open-in-view` does,
settles the record from another thread, and asserts that the store sees it —
alongside the stale entity read, kept visible so the reason is not lost.

All three require a running Docker daemon. `mvn test` skips them.

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

**Offline (69 tests, in CI).** Drives a production build of the console with the
backend pointed at a dead port. Covers route protection, expired and malformed
sessions, form validation, responsive layout down to 320px, and three
architecture guarantees: the session cookie is httpOnly, the token never appears
in the HTML sent to the browser, and a page that cannot load its data says so
rather than rendering a zero. No backend required.

**Live (34 tests, on demand).** Requires all 13 backend processes plus a seeded customer.
Covers sign-in to a dashboard showing real balances, account and transaction
history, the full money-movement journeys, loan amortization, card masking,
staff-route denial for a customer, sign-out, onboarding, and a phone viewport.

Two live groups are worth calling out. `move-money.live.spec.ts` drives deposit,
withdrawal and transfer through details, review and receipt, and then reads the
balances and transaction counts back from the API: the assertion is that the
money moved *once*, by the amount confirmed, including when confirm is
double-clicked. `rsc-boundary.live.spec.ts` checks the three surfaces a browser
sees — delivered HTML, the RSC/Flight response captured during a real client
navigation, and the DOM including `aria-label`, `title` and `data-*` — for the
signed-in customer's real account numbers, and never prints the value when it
fails.

```bash
# offline — no backend required
cd frontend && npm run test:e2e

# live — requires the full stack
docker compose up -d && ./scripts/seed-demo.sh
cd frontend && E2E_USERNAME=<printed> E2E_PASSWORD=<printed> npm run test:e2e:live
```

## Authorization tests

The authorization suites are written at the HTTP boundary rather than against the
service layer, because the control being tested is the boundary: that a denial
returns 403, and that the service is never reached. Each negative case asserts
both.

The ownership matrix covers customer A, customer B, an employee and an admin
against resource access, staff-only, admin-only, acting-for-another-user and
self-only rules. `GatewayIdentitySpoofing` covers the property the rest depends
on: a client sending `X-User-Id` and `X-User-Role` alongside a valid token has
those values replaced with the ones derived from the token.

## The PowerShell full-stack suite

`e2e-tests.ps1` drives the whole platform through the gateway: registration,
account opening, money movement, a credit-card application through Kafka to an
issued card, a loan through disbursement and repayment, KYC submission,
notifications, external rails and TOTP enrolment. 69 assertions.

It also proves the ownership rules that only a real gateway can prove. A second
customer is registered purely to be refused: they may not wire or ACH from the
first customer's account, may not read that customer's transfer by its
reference, and may not start or remove that customer's second factor. Each is a
403 through the live gateway, with a real JWT, so the check is exercised
end-to-end rather than against a mocked caller identity.

Three things about how it is written are worth stating, because each was wrong
before.

**A refusal is asserted as a refusal.** Platform statistics, the daily snapshot,
application review and KYC review are staff-only, and the suite runs with a
customer token. It used to call them and assert that data came back, so it
finished with red lines every run — the authorization was right and the test was
wrong. Those are now explicit `Assert-Refused` checks against the expected HTTP
status, using a helper that reports the status rather than swallowing the
exception and returning `$null`. Three places that quietly incremented the pass
counter when a call came back empty are gone.

**Eventual consistency is waited for, not slept through.** The card and the loan
are created by Kafka consumers. Fixed sleeps were a guess about consumer
scheduling; `Wait-For` polls until the fact holds, returns the moment it does
and fails clearly at a deadline. The one remaining sleep is a TOTP window, which
genuinely is a duration.

**It fails the process.** The suite exits non-zero when any assertion failed. It
previously printed `FAILED: n` and exited 0, so nothing could gate on it. It
also no longer prints a one-time code.

```powershell
docker compose up -d
.\e2e-tests.ps1        # exits 0 only when every assertion passed
```

## Coverage boundary

Coverage is deep on the services holding the most consequential arithmetic —
balances and amortization — plus card masking, the 2FA gate, the shared API error
contract, request correlation, the circuit-breaker policy, and resource-ownership
authorization across accounts, money movement, profiles, KYC, statistics,
payees, payments, notifications, applications, fraud alerts, loans and cards.

It is not even, and the uneven parts are worth naming.

`payment`, `notification`, `integration` and `application` services have
authorization suites but no service-layer tests: what those services *do* with a
request, once it is allowed, is covered only through the live suites.

Three services run against a real PostgreSQL through Testcontainers —
`account-service` for migrations, persistence and concurrent balance changes,
`transaction-service` for idempotent money movement under concurrency, and
`user-service` for the customer-profile migration. The rest are tested against
mocks, so a mapping or migration fault in them would surface only when the stack
runs.

`loan-service` and `credit-card-service` now have authorization suites at the
HTTP boundary, added with the ownership fix. Their arithmetic is covered by the
existing `LoanServiceImpl` unit tests; `credit-card-service` has masking tests
but no service-layer coverage of purchase, cash advance or payment.
