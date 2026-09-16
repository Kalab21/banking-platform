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
AUTH=$(api POST /api/auth/register "$(cat <<JSON
{"username":"${USERNAME}","email":"${USERNAME}@example.com","password":"${PASSWORD}",
 "firstName":"Ada","lastName":"Lovelace","phone":"5550000000"}
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

# ---------------------------------------------------------------------- done

cat <<SUMMARY

────────────────────────────────────────────────────────────
Demo customer ready.

  Console    http://localhost:3000
  Username   ${USERNAME}
  Password   ${PASSWORD}

Seeded: 2 accounts, 9 transactions, 1 beneficiary, 1 loan with
schedule and one repayment, 2 KYC documents pending review.

Credit cards and notifications populate from Kafka events, so they
may take a few seconds to appear.
────────────────────────────────────────────────────────────
SUMMARY
