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

say "Opening accounts"
CHECKING=$(api POST /api/accounts \
  "{\"userId\":${USER_ID},\"accountType\":\"CHECKING\",\"currency\":\"USD\",\"initialDeposit\":4200.00,\"overdraftLimit\":500.00}" \
  "$TOKEN" | json id)
ok "checking account ${CHECKING}"

SAVINGS=$(api POST /api/accounts \
  "{\"userId\":${USER_ID},\"accountType\":\"SAVINGS\",\"currency\":\"USD\",\"initialDeposit\":15000.00,\"overdraftLimit\":0.00}" \
  "$TOKEN" | json id)
ok "savings account ${SAVINGS}"

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

say "Opening a loan with an amortization schedule"
LOAN=$(api POST /api/loans \
  "{\"userId\":${USER_ID},\"loanType\":\"PERSONAL_LOAN\",\"principal\":10000.00,\"interestRate\":6.00,\"termMonths\":12,\"disbursementAccountId\":${CHECKING}}" \
  "$TOKEN" | json id)
if [[ -n "$LOAN" ]]; then
  ok "loan ${LOAN} — 10,000.00 over 12 months at 6.00%"
  api POST "/api/loans/${LOAN}/disburse" "{\"disbursementAccountId\":${CHECKING}}" "$TOKEN" > /dev/null
  ok "disbursed"
  api POST "/api/loans/${LOAN}/repay" "{\"amount\":860.66,\"sourceAccountId\":${CHECKING}}" "$TOKEN" > /dev/null
  ok "first instalment repaid"
fi

# ------------------------------------------------------------------------- kyc

say "Submitting KYC documents"
api POST "/api/users/${USER_ID}/kyc/documents" \
  '{"documentType":"PASSPORT","documentRef":"DEMO-PASSPORT-0001"}' "$TOKEN" > /dev/null
ok "passport"
api POST "/api/users/${USER_ID}/kyc/documents" \
  '{"documentType":"PROOF_OF_ADDRESS","documentRef":"DEMO-ADDRESS-0001"}' "$TOKEN" > /dev/null
ok "proof of address"

# ------------------------------------------------------------------ backdating

if [[ "${SEED_BACKDATE:-0}" == "1" ]]; then
  say "Spreading transaction dates (demo presentation only)"
  # Oldest transaction ~8 weeks back, newest ~2 days back, evenly spaced.
  # Credentials match docker-compose.yml; they are throwaway local values.
  if docker compose exec -T postgres \
       psql -U "${POSTGRES_USER:-bankingadmin}" -d transaction_db \
            -v ON_ERROR_STOP=1 -q \
            -v checking="${CHECKING}" -v savings="${SAVINGS}" <<'SQL'
WITH ordered AS (
  SELECT id, row_number() OVER (ORDER BY id) AS rn, count(*) OVER () AS total
  FROM transactions
  WHERE account_id IN (:checking, :savings)
)
UPDATE transactions t
SET created_at = NOW()
    - INTERVAL '2 days'
    - (INTERVAL '1 day' * ((o.total - o.rn) * (54.0 / GREATEST(o.total - 1, 1))))
FROM ordered o
WHERE t.id = o.id;
SQL
  then
    ok "transaction dates spread over the last 8 weeks"
  else
    printf '  [!!] backdating failed (is the Compose stack up?) — dates left as-is\n'
  fi
fi

# ---------------------------------------------------------------------- done

cat <<SUMMARY

────────────────────────────────────────────────────────────
Demo customer ready.

  Console    http://localhost:3000
  Username   ${USERNAME}
  Password   ${PASSWORD}

Seeded: 2 accounts, 12 transactions, 1 beneficiary, 1 loan with
schedule and one repayment, 2 KYC documents pending review.

Credit cards and notifications populate from Kafka events, so they
may take a few seconds to appear.
────────────────────────────────────────────────────────────
SUMMARY
