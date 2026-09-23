#!/usr/bin/env bash
#
# Seeds a demonstration customer through the public API.
#
# Everything here goes through the API Gateway using the same endpoints the
# console uses — no direct database writes, no production configuration, and no
# secrets. Run it against a local stack only.
#
#   docker compose up -d
#   ./scripts/seed-demo.sh
#
# Names and numbers are obviously synthetic. Re-running creates a fresh customer
# with a new suffix, so the script is safe to run repeatedly.
#
# SEED_BACKDATE=1 additionally spreads the seeded transaction timestamps over
# the preceding weeks. That is the one step that touches the database directly,
# because `created_at` is a @CreationTimestamp and is deliberately not settable
# through the API — a banking API should not let a caller choose when a
# transaction happened. It exists so the dashboard balance chart has a real date
# range in screenshots, it is off by default, and it needs the Compose stack.

set -euo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
SUFFIX="$(date +%s)"
USERNAME="demo.customer.${SUFFIX}"
PASSWORD="DemoPassword123!"

# The reviewer this stack creates. Credit applications wait on a completed
# identity check, and only a reviewer can complete one.
STAFF_USERNAME="${NORTHBANK_DEMO_STAFF_USERNAME:-northbank.reviewer}"
STAFF_PASSWORD="${NORTHBANK_DEMO_STAFF_PASSWORD:-ReviewerDemo2026!}"

say() { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok()  { printf '  [ok] %s\n' "$1"; }

api() {
  local method="$1" path="$2" body="${3:-}" auth="${4:-}"
  local args=(-s -X "$method" "${GATEWAY}${path}" -H "Content-Type: application/json")
  # Money movement requires an Idempotency-Key and the other endpoints ignore
  # it. A fresh value per call is what this script wants: every seeded
  # transaction is meant to be a distinct one, not a retry of the last.
  args+=(-H "Idempotency-Key: seed-$(date +%s)-${RANDOM}-${RANDOM}")
  [[ -n "$auth" ]] && args+=(-H "Authorization: Bearer ${auth}")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}"
}

# Reads one top-level field from a JSON response. Uses node rather than a regex:
# a greedy pattern for "id" also matches inside "userId", which silently yields
# the wrong value.
json() {
  node -e '
    let raw = "";
    process.stdin.on("data", (c) => (raw += c));
    process.stdin.on("end", () => {
      try {
        const value = JSON.parse(raw)[process.argv[1]];
        process.stdout.write(value === undefined || value === null ? "" : String(value));
      } catch {
        process.stdout.write("");
      }
    });
  ' "$1"
}

# The same, for an endpoint that answers with a list: reads one field off the
# first element. Used to pick up a product that was created for this customer.
json_first() {
  node -e '
    let raw = "";
    process.stdin.on("data", (c) => (raw += c));
    process.stdin.on("end", () => {
      try {
        const list = JSON.parse(raw);
        const value = Array.isArray(list) && list.length ? list[0][process.argv[1]] : undefined;
        process.stdout.write(value === undefined || value === null ? "" : String(value));
      } catch {
        process.stdout.write("");
      }
    });
  ' "$1"
}

# A loan and a card are issued by the service that owns them, in response to
# the approval event application-service publishes — not by the call that
# submits the application. So the product appears a moment after the
# application is answered, and the seed waits for it rather than assuming it.
await_product() {
  local path="$1" token="$2"
  for _ in $(seq 1 45); do
    local id
    id=$(api GET "$path" "" "$token" | json_first id)
    if [[ -n "$id" ]]; then printf '%s' "$id"; return 0; fi
    sleep 1
  done
  return 1
}

say "Checking the gateway at ${GATEWAY}"
if ! curl -sf -o /dev/null "${GATEWAY}/actuator/health"; then
  echo "  Gateway is not responding. Start the stack first: docker compose up -d" >&2
  exit 1
fi
ok "gateway reachable"

# ---------------------------------------------------------------- the customer

say "Creating demo customer"
# Onboarding requires a full profile: date of birth, a mailing address and an
# identity number. Every value below is synthetic — example.com, the 555-01xx
# range reserved for fiction, and a Social Security number reserved for
# demonstration use. The server keeps only the last four digits of that number.
AUTH=$(api POST /api/auth/register "$(cat <<JSON
{"username":"${USERNAME}","email":"${USERNAME}@example.com","password":"${PASSWORD}",
 "firstName":"Ada","lastName":"Lovelace","dateOfBirth":"1990-01-15","phone":"2405550148",
 "streetAddress":"123 Example Street","city":"Silver Spring","state":"MD","postalCode":"20910",
 "ssn":"123-45-6789"}
JSON
)")
TOKEN=$(echo "$AUTH" | json token)
USER_ID=$(echo "$AUTH" | json userId)
[[ -n "$TOKEN" && -n "$USER_ID" ]] || { echo "  Registration failed: $AUTH" >&2; exit 1; }
ok "customer ${USERNAME} (id ${USER_ID})"

# ------------------------------------------------------------------- accounts

# An account opens empty — the API has no field for an opening balance,
# because a balance that appears with no payer behind it is money invented.
# The demo's starting funds arrive the way a customer's would: as deposits,
# each one a transaction that can be listed and totalled.
say "Opening accounts"
CHECKING=$(api POST /api/accounts \
  "{\"userId\":${USER_ID},\"accountType\":\"CHECKING\",\"currency\":\"USD\",\"overdraftLimit\":500.00}" \
  "$TOKEN" | json id)
ok "checking account ${CHECKING} (opened at 0.00)"

SAVINGS=$(api POST /api/accounts \
  "{\"userId\":${USER_ID},\"accountType\":\"SAVINGS\",\"currency\":\"USD\",\"overdraftLimit\":0.00}" \
  "$TOKEN" | json id)
ok "savings account ${SAVINGS} (opened at 0.00)"

say "Funding the accounts"
fund() {
  api POST /api/transactions/deposit \
    "{\"accountId\":$1,\"amount\":$2,\"description\":\"$3\"}" "$TOKEN" > /dev/null
  ok "$3"
}
fund "${CHECKING}" 4200.00  "Opening deposit — checking"
fund "${SAVINGS}"  15000.00 "Opening deposit — savings"

# --------------------------------------------------------------- transactions

say "Recording transactions"
seed_tx() {
  api POST "/api/transactions/$1" \
    "{\"accountId\":${CHECKING},\"amount\":$2,\"description\":\"$3\"}" "$TOKEN" > /dev/null
  ok "$3"
}

seed_tx deposit  3150.00 "Salary — Ada Lovelace Ltd"
seed_tx withdraw 1450.00 "Rent payment"
seed_tx withdraw  268.40 "Utilities"
seed_tx deposit   420.00 "Expense reimbursement"
seed_tx withdraw   96.75 "Groceries"
seed_tx withdraw  240.00 "Transit season ticket"
seed_tx deposit   310.00 "Consulting invoice"
seed_tx withdraw  132.20 "Mobile and broadband"

# A couple on savings too, so the account detail page shows a real history
# rather than the single incoming transfer below.
seed_tx_on() {
  api POST "/api/transactions/$1" \
    "{\"accountId\":${SAVINGS},\"amount\":$2,\"description\":\"$3\"}" "$TOKEN" > /dev/null
  ok "$3 (savings)"
}
seed_tx_on deposit  1200.00 "Quarterly bonus"
seed_tx_on deposit    64.18 "Interest payment"
seed_tx_on withdraw  300.00 "Transfer to brokerage"

say "Transferring between own accounts"
api POST /api/transactions/transfer \
  "{\"fromAccountId\":${CHECKING},\"toAccountId\":${SAVINGS},\"amount\":750.00,\"description\":\"Monthly saving\"}" \
  "$TOKEN" > /dev/null
ok "750.00 checking to savings"

# ------------------------------------------------------------------ beneficiary

say "Adding a beneficiary"
api POST /api/payments/beneficiaries "$(cat <<JSON
{"userId":${USER_ID},"name":"Northwind Properties","nickname":"Landlord",
 "accountNumber":"0000111122","bankName":"Example Bank","routingNumber":"021000021",
 "beneficiaryType":"EXTERNAL_ACH","currency":"USD"}
JSON
)" "$TOKEN" > /dev/null
ok "Northwind Properties"

# ------------------------------------------------------------------------ loan

# ------------------------------------------------------------------------- kyc
#
# Credit needs a completed identity check. A customer registers PENDING and
# reaches IN_REVIEW by submitting documents; only a reviewer moves them to
# APPROVED, so the seed does what a real customer's application would wait for.
# A deposit account needs none of this, which is why the accounts above opened
# first.

say "Submitting KYC documents"
api POST "/api/users/${USER_ID}/kyc/documents"   '{"documentType":"PASSPORT","documentRef":"DEMO-PASSPORT-0001"}' "$TOKEN" > /dev/null
ok "passport"
api POST "/api/users/${USER_ID}/kyc/documents"   '{"documentType":"PROOF_OF_ADDRESS","documentRef":"DEMO-ADDRESS-0001"}' "$TOKEN" > /dev/null
ok "proof of address"

say "Completing the identity check as a reviewer"
STAFF_TOKEN=$(api POST /api/auth/login   "{\"username\":\"${STAFF_USERNAME}\",\"password\":\"${STAFF_PASSWORD}\"}" | json token)
if [[ -z "$STAFF_TOKEN" ]]; then
  echo "  Could not sign in as ${STAFF_USERNAME}. user-service creates this account when"
  echo "  NORTHBANK_DEMO_STAFF_ENABLED is true; see docker-compose.yml."
  exit 1
fi
api PUT "/api/users/${USER_ID}/kyc/status?status=APPROVED" "" "$STAFF_TOKEN" > /dev/null
ok "identity check approved by ${STAFF_USERNAME}"

say "Applying for a loan and letting the bank issue it"
# The seed asks for a loan the way a customer does. It does not state a rate or
# a term, because those are the bank's to decide, and there is no longer an
# endpoint that would accept them if it tried.
LOAN_APP=$(api POST /api/applications "$(cat <<JSON
{"userId":${USER_ID},"applicationType":"PERSONAL_LOAN","requestedAmount":10000.00,
 "termMonths":12,"currency":"USD","purpose":"Home improvement",
 "annualIncome":90000.00,"monthlyDebtObligations":450.00}
JSON
)" "$TOKEN" | json id)
ok "personal loan application ${LOAN_APP} submitted"

# Approval is an offer, not a product. Nothing is created until the customer
# accepts the terms they were shown, so the seed accepts them the way a
# customer would.
LOAN_OFFER=$(api POST "/api/applications/${LOAN_APP}/offer/accept" "" "$TOKEN")
ok "offer accepted — $(printf '%s' "$LOAN_OFFER" | json approvedAmount) over $(printf '%s' "$LOAN_OFFER" | json termMonths) months at $(printf '%s' "$LOAN_OFFER" | json apr)%"

LOAN="$(await_product "/api/loans/user/${USER_ID}" "$TOKEN" || true)"
if [[ -n "$LOAN" ]]; then
  LOAN_JSON="$(api GET "/api/loans/${LOAN}" "" "$TOKEN")"
  ok "loan ${LOAN} — $(printf '%s' "$LOAN_JSON" | json principal) over $(printf '%s' "$LOAN_JSON" | json termMonths) months at $(printf '%s' "$LOAN_JSON" | json interestRate)%"
  api POST "/api/loans/${LOAN}/disburse" "{\"disbursementAccountId\":${CHECKING}}" "$TOKEN" > /dev/null
  ok "disbursed"
  api POST "/api/loans/${LOAN}/repay" "{\"amount\":860.66,\"sourceAccountId\":${CHECKING}}" "$TOKEN" > /dev/null
  ok "first instalment repaid"
else
  ok "loan application submitted; the loan had not been issued yet when the seed finished"
fi

# ------------------------------------------------------------------ credit card

# A card with a little history on it. The console's cards page is a real page
# with a real empty state, and an empty state is what a demo customer saw here
# before: nothing in the seed ever issued a card.
say "Applying for a credit card and letting the bank issue it"
# A card applicant states what they earn and what they already owe. The tier,
# the limit and the APR are decided for them.
CARD_APP=$(api POST /api/applications "$(cat <<JSON
{"userId":${USER_ID},"applicationType":"CREDIT_CARD","currency":"USD",
 "purpose":"Everyday spending","annualIncome":90000.00,"monthlyDebtObligations":450.00}
JSON
)" "$TOKEN" | json id)
ok "credit card application ${CARD_APP} submitted"

CARD_OFFER=$(api POST "/api/applications/${CARD_APP}/offer/accept" "" "$TOKEN")
ok "offer accepted — $(printf '%s' "$CARD_OFFER" | json cardTier) at $(printf '%s' "$CARD_OFFER" | json creditLimit) limit, $(printf '%s' "$CARD_OFFER" | json apr)% APR"

CARD="$(await_product "/api/credit-cards/user/${USER_ID}" "$TOKEN" || true)"
if [[ -n "$CARD" ]]; then
  CARD_JSON="$(api GET "/api/credit-cards/${CARD}" "" "$TOKEN")"
  ok "$(printf '%s' "$CARD_JSON" | json cardType) card — $(printf '%s' "$CARD_JSON" | json creditLimit) limit at $(printf '%s' "$CARD_JSON" | json apr)% APR"
  # Purchases are simulated by the reviewer, not the cardholder: a customer
  # views card transactions, they do not invent them.
  card_purchase() {
    api POST "/api/credit-cards/${CARD}/purchase"       "{\"amount\":$1,\"description\":\"$2\",\"merchantName\":\"$2\",\"merchantCategory\":\"$3\"}"       "$STAFF_TOKEN" > /dev/null
    ok "$2"
  }
  card_purchase 186.40 "Harborline Groceries" "GROCERIES"
  card_purchase  64.99 "Meridian Books"       "RETAIL"
  card_purchase 214.16 "Seaboard Airlines"    "TRAVEL"
  api POST "/api/credit-cards/${CARD}/payment"     "{\"amount\":200.00,\"sourceAccountId\":${CHECKING}}" "$TOKEN" > /dev/null
  ok "200.00 paid off the balance"
fi

# ------------------------------------------------------------------ backdating

if [[ "${SEED_BACKDATE:-0}" == "1" ]]; then
  say "Spreading transaction dates (demo presentation only)"
  # Oldest transaction ~8 weeks back, newest ~2 days back, evenly spaced.
  # Credentials match docker-compose.yml; they are throwaway local values.
  if docker compose exec -T postgres \
       psql -U "${POSTGRES_USER:-bankingadmin}" -d transaction_db \
            -v ON_ERROR_STOP=1 -q \
            -v checking="${CHECKING}" -v savings="${SAVINGS}" <<'SQL'
-- A transfer is two rows, and they happened at the same moment. Spacing by
-- row id alone pushed the debit and the credit days apart, which is visible in
-- the history and is not something any real ledger would show. Rows are grouped
-- by the reference pair first, and each group is dated once.
WITH grouped AS (
  SELECT id, LEAST(transaction_ref, related_transaction_ref) AS grp
  FROM transactions
  WHERE account_id IN (:checking, :savings)
),
ordered AS (
  SELECT grp,
         row_number() OVER (ORDER BY first_id) AS rn,
         count(*) OVER () AS total
  FROM (SELECT grp, MIN(id) AS first_id FROM grouped GROUP BY grp) g
)
UPDATE transactions t
SET created_at = NOW()
    - INTERVAL '2 days'
    - (INTERVAL '1 day' * ((o.total - o.rn) * (54.0 / GREATEST(o.total - 1, 1))))
FROM grouped p
JOIN ordered o ON o.grp = p.grp
WHERE t.id = p.id;
SQL
  then
    ok "transaction dates spread over the last 8 weeks"
  else
    printf '  [!!] backdating failed (is the Compose stack up?) — dates left as-is\n'
  fi

  # The accounts have to predate their own history. Without this the detail
  # page reads "Opened today" above a deposit from August.
  if docker compose exec -T postgres \
       psql -U "${POSTGRES_USER:-bankingadmin}" -d account_db \
            -v ON_ERROR_STOP=1 -q \
            -v checking="${CHECKING}" -v savings="${SAVINGS}" <<'SQL'
UPDATE accounts
SET created_at = NOW() - INTERVAL '10 weeks'
WHERE id IN (:checking, :savings);
SQL
  then
    ok "accounts opened before their first transaction"
  else
    printf '  [!!] account opening dates left as-is\n'
  fi

  # And the customer has to predate their own accounts, or the profile says
  # "customer since" a date after the account it is attached to was opened.
  if docker compose exec -T postgres \
       psql -U "${POSTGRES_USER:-bankingadmin}" -d user_db \
            -v ON_ERROR_STOP=1 -q \
            -v username="'${USERNAME}'" <<'SQL'
UPDATE users
SET created_at = NOW() - INTERVAL '11 weeks'
WHERE username = :username;
SQL
  then
    ok "customer registered before their accounts were opened"
  else
    printf '  [!!] customer registration date left as-is\n'
  fi
fi

# ---------------------------------------------------------------------- done

cat <<SUMMARY

────────────────────────────────────────────────────────────
Demo customer ready.

  Console    http://localhost:3000
  Username   ${USERNAME}
  Password   ${PASSWORD}

Seeded: 2 accounts opened empty and then funded by deposit,
14 transactions, 1 beneficiary, 1 loan with schedule and one
repayment, 1 credit card with three purchases and a payment, and
2 KYC documents pending review.

Credit cards and notifications populate from Kafka events, so they
may take a few seconds to appear.
────────────────────────────────────────────────────────────
SUMMARY
