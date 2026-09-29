# Banking Platform — E2E Test Suite (Days 17+)
# Prerequisites: docker compose up -d (all services healthy)
# Run: .\e2e-tests.ps1

$GW = "http://localhost:8080"
$PASS = 0; $FAIL = 0

function Assert($label, $condition, $detail = "") {
    if ($condition) {
        Write-Host "  [PASS] $label" -ForegroundColor Green
        $script:PASS++
    } else {
        Write-Host "  [FAIL] $label $detail" -ForegroundColor Red
        $script:FAIL++
    }
}

# Returns the HTTP status of a request rather than its body, so a refusal can
# be asserted as a refusal. The helpers below swallow the exception and hand
# back $null, which cannot tell 403 apart from a service being down.
function Status($method, $url, $token = $null, $body = $null) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    if ($method -eq "POST") { $headers["Idempotency-Key"] = "e2e-$([guid]::NewGuid())" }
    try {
        $args = @{ Uri = $url; Method = $method; Headers = $headers; TimeoutSec = $script:WRITE_TIMEOUT }
        if ($body) { $args["Body"] = ($body | ConvertTo-Json -Depth 10) }
        $response = Invoke-WebRequest @args -UseBasicParsing
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

# An authorization assertion, stated as one: this caller must be refused, with
# this status, and the refusal is the pass condition.
function Assert-Refused($label, $method, $url, $token, $expected = 403, $body = $null) {
    $status = Status $method $url $token $body
    Assert "$label (expects $expected)" ($status -eq $expected) "got HTTP $status"
}

<#
    Waits for an eventually-consistent condition instead of guessing at a sleep.

    The card and the loan are created by Kafka consumers, so how long they take
    depends on broker and consumer scheduling, not on a number anyone can pick
    in advance. A fixed sleep either wastes time or fails on a slow machine;
    this returns the moment the condition holds and says so clearly when the
    deadline passes. It reports whether the condition held; the caller reads the
    value back itself.
#>
<#
    How many records a response actually carried.

    Two PowerShell traps meet here. @($null) has a Count of 1, so wrapping a
    failed call in @() produces a "list of one" that passes a Count -gt 0 check
    while holding nothing — a false pass, which is worse than a red line. And a
    function that returns @(...) unrolls it on the way out, so a single-element
    result arrives at the caller as a bare object whose .Count is empty.

    Returning the number sidesteps both: an int cannot be unrolled, and a
    failed call counts as zero.
#>
function CountOf($value) {
    return @($value | Where-Object { $null -ne $_ }).Count
}

function Wait-For($label, [scriptblock]$condition, $timeoutSeconds = 180, $intervalSeconds = 3) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $elapsed = 0
    while ((Get-Date) -lt $deadline) {
        if (& $condition) {
            Write-Host "  [wait] $label settled after ${elapsed}s" -ForegroundColor DarkGray
            return $true
        }
        Start-Sleep -Seconds $intervalSeconds
        $elapsed += $intervalSeconds
    }
    Write-Host "  [wait] $label did not settle within ${timeoutSeconds}s" -ForegroundColor Red
    return $false
}

<#
    Client timeouts, not service budgets.

    These drive a cold stack: the first request down any path pays class
    loading, connection-pool warm-up and, for money movement, a first hop to
    account-service. Thirteen JVMs sharing one development machine make that
    slow in a way that says nothing about correctness, and a 30-second client
    timeout was failing disbursement on its first call and cascading into the
    repayment that follows it. Generous here so a timeout means something is
    actually wrong.
#>
$script:READ_TIMEOUT = 60
$script:WRITE_TIMEOUT = 120

function Post($url, $body, $token = $null) {
    # Money movement requires an Idempotency-Key; the other endpoints ignore it.
    # A new value per call, because each step here is a distinct operation.
    $headers = @{ "Content-Type" = "application/json"; "Idempotency-Key" = "e2e-$([guid]::NewGuid())" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    try { return Invoke-RestMethod $url -Method POST -Body ($body | ConvertTo-Json -Depth 10) -Headers $headers -TimeoutSec $script:WRITE_TIMEOUT }
    catch { Write-Host "    POST $url => $($_.Exception.Message)" -ForegroundColor DarkYellow; return $null }
}

function Get($url, $token = $null) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    try { return Invoke-RestMethod $url -Method GET -Headers $headers -TimeoutSec $script:READ_TIMEOUT }
    catch { Write-Host "    GET $url => $($_.Exception.Message)" -ForegroundColor DarkYellow; return $null }
}

function Put($url, $body, $token = $null) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    try { return Invoke-RestMethod $url -Method PUT -Body ($body | ConvertTo-Json -Depth 10) -Headers $headers -TimeoutSec $script:WRITE_TIMEOUT }
    catch { Write-Host "    PUT $url => $($_.Exception.Message)" -ForegroundColor DarkYellow; return $null }
}

function Delete($url, $body, $token = $null) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    try { return Invoke-RestMethod $url -Method DELETE -Body ($body | ConvertTo-Json -Depth 10) -Headers $headers -TimeoutSec $script:WRITE_TIMEOUT }
    catch { Write-Host "    DELETE $url => $($_.Exception.Message)" -ForegroundColor DarkYellow; return $null }
}

# Computes a TOTP code from a base32 secret (RFC 6238, SHA-1, 6 digits, 30s window)
function Get-TOTP($secret) {
    $base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    $bits = ""
    foreach ($char in $secret.ToUpper().ToCharArray()) {
        $idx = $base32Chars.IndexOf($char)
        if ($idx -ge 0) { $bits += [Convert]::ToString($idx, 2).PadLeft(5, '0') }
    }
    $keyBytes = @()
    for ($i = 0; $i + 8 -le $bits.Length; $i += 8) {
        $keyBytes += [Convert]::ToByte($bits.Substring($i, 8), 2)
    }
    $timeSlot = [long][Math]::Floor(([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) / 30)
    $timeBytes = [BitConverter]::GetBytes($timeSlot)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($timeBytes) }
    $hmac = New-Object System.Security.Cryptography.HMACSHA1
    $hmac.Key = [byte[]]$keyBytes
    $hash = $hmac.ComputeHash([byte[]]$timeBytes)
    $offset = $hash[19] -band 0x0F
    $code = (($hash[$offset]     -band 0x7F) -shl 24) -bor `
            (($hash[$offset + 1] -band 0xFF) -shl 16) -bor `
            (($hash[$offset + 2] -band 0xFF) -shl 8)  -bor `
             ($hash[$offset + 3] -band 0xFF)
    return ($code % 1000000).ToString("000000")
}

# ── SETUP: shared user ────────────────────────────────────────────────────────

Write-Host "`n=== SETUP ===" -ForegroundColor Cyan

$ts = Get-Date -Format "yyyyMMddHHmmss"
# Onboarding requires a full profile. Every value is synthetic: example.com,
# the 555-01xx range reserved for fiction, and a Social Security number
# reserved for demonstration use. The server keeps only its last four digits.
# Note "phone", not "phoneNumber" — the latter was never a field on the
# request and was being silently discarded.
$regBody = @{
    username      = "e2euser$ts"
    email         = "e2e$ts@example.com"
    password      = "Test1234!"
    firstName     = "E2E"
    lastName      = "User"
    dateOfBirth   = "1990-01-15"
    phone         = "2405550148"
    streetAddress = "123 Example Street"
    city          = "Silver Spring"
    state         = "MD"
    postalCode    = "20910"
    ssn           = "123-45-6789"
}
$auth = Post "$GW/api/auth/register" $regBody
Assert "Register user" ($auth -and $auth.token)
$TOKEN = $auth.token
$USER_ID = $auth.userId

if (-not $USER_ID) {
    # fallback: login to get userId from token claims (decode JWT)
    $payload = $auth.token.Split('.')[1]
    $padded = $payload.PadRight($payload.Length + (4 - $payload.Length % 4) % 4, '=')
    $decoded = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($padded)) | ConvertFrom-Json
    $USER_ID = $decoded.userId
}
Write-Host "  UserId=$USER_ID"

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 1: Account Opening ===" -ForegroundColor Cyan

# A deposit account is not a request for money, so the application carries no
# amount. The API refuses one rather than ignoring it.
$appBody = @{ userId=$USER_ID; applicationType="CHECKING_ACCOUNT"; currency="USD"; purpose="Primary checking account" }
$app1 = Post "$GW/api/applications" $appBody $TOKEN
Assert "Submit CHECKING_ACCOUNT application" ($app1 -and $app1.id)
# PROVISIONED, not DISBURSED: an account is opened, never disbursed, and
# this one really does exist because account-service answered with its id.
Assert "Application provisioned (score >= 0)" ($app1.status -eq "PROVISIONED")
Assert-Refused "A deposit application cannot state an amount" "POST" `
    "$GW/api/applications" $TOKEN 400 `
    @{ userId=$USER_ID; applicationType="SAVINGS_ACCOUNT"; currency="USD"; requestedAmount=500.00 }
$ACCOUNT_ID = $app1.productId
if (-not $ACCOUNT_ID -or $ACCOUNT_ID -le 0) {
    # Fallback: look up accounts for user
    $userAccounts = Get "$GW/api/accounts/user/$USER_ID" $TOKEN
    if ($userAccounts -and $userAccounts.Count -gt 0) { $ACCOUNT_ID = $userAccounts[0].id }
}
Assert "Account created (productId set)" ($ACCOUNT_ID -and $ACCOUNT_ID -gt 0)
# An overdraft limit is credit the bank extends. Opening an account used to
# accept any limit the customer stated, and the account could be withdrawn
# against at once.
Assert-Refused "A customer cannot choose their own overdraft limit" "POST" `
    "$GW/api/accounts" $TOKEN 403 @{ userId=$USER_ID; accountType="CHECKING"; currency="USD"; overdraftLimit=1000000 }

$acct = Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN
Assert "Account is ACTIVE" ($acct.status -eq "ACTIVE")
Assert "Account type is CHECKING" ($acct.accountType -eq "CHECKING")
# An application is a request, not a deposit. This one asked for 500.00 and the
# account still opens empty: the requested amount describes what the customer
# wants, and nothing about it is a payment into the account.
Assert "Account opens at 0.00" ([decimal]$acct.balance -eq 0)
Assert "requestedAmount did not become a balance" ([decimal]$acct.balance -ne 500.00)
Write-Host "  AccountId=$ACCOUNT_ID  Balance=$($acct.balance)"

# Fund it the way a customer would, so the flows below have money to move.
$fund = Post "$GW/api/transactions/deposit" @{ accountId=$ACCOUNT_ID; amount=500.00; description="Opening deposit" } $TOKEN
Assert "Opening deposit accepted" ($fund -ne $null)
$acctFunded = Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN
Assert "Balance after opening deposit = 500.00" ([decimal]$acctFunded.balance -eq 500.00)
# Balances are held in cents. A fraction of a cent used to be rounded by the
# database on write, so three deposits of 0.015 stored 0.06.
Assert-Refused "A deposit of a fraction of a cent is refused" "POST" `
    "$GW/api/transactions/deposit" $TOKEN 400 @{ accountId=$ACCOUNT_ID; amount=0.015; description="Sub-cent" }
Write-Host "  Funded balance=$($acctFunded.balance)"

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 4: Overdraft ===" -ForegroundColor Cyan

# Deposit to set known balance of 100
$dep = Post "$GW/api/transactions/deposit" @{ accountId=$ACCOUNT_ID; amount=100.00; description="E2E deposit" } $TOKEN
# Withdraw to bring to exactly 100 (original was 500, add 100 = 600, withdraw 500)
$wd1 = Post "$GW/api/transactions/withdraw" @{ accountId=$ACCOUNT_ID; amount=500.00; description="E2E balance reset" } $TOKEN
Assert "Withdraw to set balance=100" ($wd1 -ne $null)

$acctCheck = Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN
Write-Host "  Balance before overdraft: $($acctCheck.balance)"

# Withdraw 400 — triggers overdraft (balance=100, overdraft_limit=500)
$wd2 = Post "$GW/api/transactions/withdraw" @{ accountId=$ACCOUNT_ID; amount=400.00; description="Overdraft trigger" } $TOKEN
Assert "Overdraft withdrawal allowed" ($wd2 -ne $null)

$acctAfter = Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN
Assert "Account status = OVERDRAWN" ($acctAfter.status -eq "OVERDRAWN")
Write-Host "  Status=$($acctAfter.status)  Balance=$($acctAfter.balance)  OverdraftBalance=$($acctAfter.overdraftBalance)"

# Deposit to clear overdraft
$dep2 = Post "$GW/api/transactions/deposit" @{ accountId=$ACCOUNT_ID; amount=450.00; description="Clear overdraft" } $TOKEN
Assert "Deposit to clear overdraft" ($dep2 -ne $null)

$acctCleared = Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN
Assert "Account status = ACTIVE after clearing" ($acctCleared.status -eq "ACTIVE")
# A transfer to an account that does not exist used to debit the source first
# and then fail the credit, leaving a half-applied transfer. It is refused now,
# and nothing leaves the account.
$beforeBadTransfer = [decimal](Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN).balance
Assert-Refused "A transfer to an account that does not exist is refused" "POST" `
    "$GW/api/transactions/transfer" $TOKEN 422 @{ fromAccountId=$ACCOUNT_ID; toAccountId=99999999; amount=10.00; description="Mistyped account" }
Assert "A refused transfer takes nothing from the source" ([decimal](Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN).balance -eq $beforeBadTransfer)
# 100 - 400 left -35 on the balance (the fee) and 300 overdrawn: a net -335.
# The 450 deposit repays the 300 first, so 115 reaches the balance. Counting the
# repaid part twice used to leave 415.
Assert "The deposit repaid the overdraft instead of adding on top of it" ([decimal]$acctCleared.balance -eq 115.00 -and [decimal]$acctCleared.overdraftBalance -eq 0) "balance=$($acctCleared.balance) overdraft=$($acctCleared.overdraftBalance)"
# 115 on the balance and a 500 limit: 615 fits the limit, but the overdraft fee
# it triggers does not. The fee used to be charged anyway, 35 past the limit.
Assert-Refused "A withdrawal whose overdraft fee would pass the limit is refused" "POST" `
    "$GW/api/transactions/withdraw" $TOKEN 422 @{ accountId=$ACCOUNT_ID; amount=615.00; description="Past the limit with the fee" }
Write-Host "  Status=$($acctCleared.status)  Balance=$($acctCleared.balance)"

# ─────────────────────────────────────────────────────────────────────────────
# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== STAFF: identity check ===" -ForegroundColor Cyan

# Credit waits on a completed identity check. A customer registers PENDING and
# reaches IN_REVIEW by submitting documents; only a reviewer moves them to
# APPROVED. Deposit accounts need none of it, which is why FLOW 1 opened first.
$STAFF_USERNAME = if ($env:NORTHBANK_DEMO_STAFF_USERNAME) { $env:NORTHBANK_DEMO_STAFF_USERNAME } else { "northbank.reviewer" }
$STAFF_PASSWORD = if ($env:NORTHBANK_DEMO_STAFF_PASSWORD) { $env:NORTHBANK_DEMO_STAFF_PASSWORD } else { "ReviewerDemo2026!" }

$staffAuth = Post "$GW/api/auth/login" @{ username=$STAFF_USERNAME; password=$STAFF_PASSWORD }
Assert "A reviewer can sign in" ($staffAuth -and $staffAuth.token)
$STAFF_TOKEN = $staffAuth.token

# Staff act for the bank, not as the customer and not for themselves.
if ($STAFF_TOKEN) {
    $STAFF_ID = $staffAuth.userId
    Assert-Refused "Staff cannot withdraw from a customer's account" "POST" `
        "$GW/api/transactions/withdraw" $STAFF_TOKEN 403 @{ accountId=$ACCOUNT_ID; amount=5.00; description="Staff withdrawal" }
    Assert-Refused "Staff cannot transfer out of a customer's account" "POST" `
        "$GW/api/transactions/transfer" $STAFF_TOKEN 403 @{ fromAccountId=$ACCOUNT_ID; toAccountId=$ACCOUNT_ID; amount=5.00 }
    Assert-Refused "Staff cannot change their own credit score" "PUT" `
        "$GW/api/users/$STAFF_ID/credit-score" $STAFF_TOKEN 403 @{ delta=100; reason="Self" }
    # A change larger than the whole score range is refused; one near the
    # int limit used to wrap negative and set the score to its floor.
    Assert-Refused "A credit score change beyond the score range is refused" "PUT" `
        "$GW/api/users/$USER_ID/credit-score" $STAFF_TOKEN 400 @{ delta=2147483647; reason="Overflow" }
    # A customer asking about a name that is not theirs gets the same answer
    # whether or not it exists.
    Assert-Refused "A username that does not exist is not revealed as missing" "GET" `
        "$GW/api/users/username/no.such.user.$ts" $TOKEN 403
}

if ($STAFF_TOKEN) {
    # The customer submits what the check needs. Submitting moves them from
    # PENDING to IN_REVIEW — started, and not yet good enough to lend against.
    $kycDoc1 = Post "$GW/api/users/$USER_ID/kyc/documents" @{ documentType="PASSPORT"; documentRef="s3://kyc-docs/passport-e2e-$ts.jpg" } $TOKEN
    Assert "Submit PASSPORT document" ($kycDoc1 -and $kycDoc1.id)
    Assert "Document status = SUBMITTED" ($kycDoc1.status -eq "SUBMITTED")
    $DOC1_ID = $kycDoc1.id

    $kycDoc2 = Post "$GW/api/users/$USER_ID/kyc/documents" @{ documentType="PROOF_OF_ADDRESS"; documentRef="s3://kyc-docs/address-e2e-$ts.pdf" } $TOKEN
    Assert "Submit PROOF_OF_ADDRESS document" ($kycDoc2 -and $kycDoc2.id)

    $docs = Get "$GW/api/users/$USER_ID/kyc/documents" $TOKEN
    Assert "GET KYC documents returns list" ((CountOf $docs) -ge 2)

    $userInReview = Get "$GW/api/users/$USER_ID" $TOKEN
    Assert "User KYC status = IN_REVIEW after document submission" ($userInReview.kycStatus -eq "IN_REVIEW")

    # A customer cannot complete their own identity check, however politely
    # they ask. That refusal is the assertion.
    Assert-Refused "A customer cannot approve their own identity check" "PUT" `
        "$GW/api/users/$USER_ID/kyc/status?status=APPROVED" $TOKEN 403

    Assert-Refused "A customer cannot approve their own KYC document" "PUT" `
        "$GW/api/kyc/documents/$DOC1_ID/review" $TOKEN 403 @{ status="APPROVED"; reviewedBy=1 }

    # A reviewer decides each document. The reviewer recorded is the member of
    # staff who is signed in; a reviewedBy in the body is ignored, so a review
    # cannot be attributed to someone else.
    $staffId = $staffAuth.userId
    $docReviewed = Put "$GW/api/kyc/documents/$DOC1_ID/review" @{ status="APPROVED"; reviewedBy=1 } $STAFF_TOKEN
    Assert "A reviewer approves a document, recorded under their own id" ($docReviewed -and $docReviewed.status -eq "APPROVED" -and $docReviewed.reviewedBy -eq $staffId) "reviewedBy=$($docReviewed.reviewedBy) staff=$staffId"
    Assert-Refused "A rejection with no reason is refused" "PUT" `
        "$GW/api/kyc/documents/$($kycDoc2.id)/review" $STAFF_TOKEN 400 @{ status="REJECTED" }
    Assert-Refused "A reviewer cannot decide their own identity check" "PUT" `
        "$GW/api/users/$staffId/kyc/status?status=APPROVED" $STAFF_TOKEN 403

    $kycDone = Put "$GW/api/users/$USER_ID/kyc/status?status=APPROVED" $null $STAFF_TOKEN
    Assert "A reviewer completes the identity check" ($kycDone -and $kycDone.kycStatus -eq "APPROVED")
}

# An account holding money cannot be closed: closing it stranded the money.
# OVERDRAWN follows the balance and is not a status anyone sets.
Assert-Refused "Staff cannot close an account that holds money" "PUT" `
    "$GW/api/accounts/$ACCOUNT_ID/status?status=CLOSED" $STAFF_TOKEN 409
Assert-Refused "Staff cannot mark an account overdrawn by hand" "PUT" `
    "$GW/api/accounts/$ACCOUNT_ID/status?status=OVERDRAWN" $STAFF_TOKEN 409
Assert "The refused close left the account open" ((Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN).status -ne "CLOSED")

Write-Host "`n=== FLOW 2: Credit Card Lifecycle ===" -ForegroundColor Cyan

# Apply for credit card — will auto-reject (score 0 < 650), then manually approve
# Northbank sets the limit and the APR, so the applicant states what they
# earn and what they already owe, and nothing about the card itself.
$ccAppBody = @{ userId=$USER_ID; applicationType="CREDIT_CARD"; currency="USD"; purpose="Personal credit card"; annualIncome=90000.00; monthlyDebtObligations=450.00 }
$ccApp = Post "$GW/api/applications" $ccAppBody $TOKEN
Assert "Submit CREDIT_CARD application" ($ccApp -and $ccApp.id)
$CC_APP_ID = $ccApp.id
# If auto-rejected (score < 650), manually approve; if auto-approved (score >= 650), skip review
if ($ccApp.status -eq "REJECTED") {
    Assert "Application auto-rejected (score 0 < 650)" $true
    # Manual review is staff-only, so a customer token is refused here. That
    # refusal is the assertion: the endpoint exists and the role check holds.
    Assert-Refused "Customer cannot review their own application" "PUT" `
        "$GW/api/applications/$CC_APP_ID/review" $TOKEN 403 `
        @{ decision="APPROVE"; reviewerNotes="Manual E2E approval" }
} else {
    # An approval is an offer, not a card. Nothing is created until the
    # customer accepts the terms they were shown.
    Assert "Application reached an offer" ($ccApp.status -eq "OFFERED")
    Assert "No product id is claimed before acceptance" ($null -eq $ccApp.productId)

    $ccOffer = Get "$GW/api/applications/$CC_APP_ID/offers" $TOKEN
    Assert "An offer was made" ((CountOf $ccOffer) -gt 0)
    $ccTerms = @($ccOffer)[0]
    Assert "The offer names a tier" ($null -ne $ccTerms.cardTier)
    Assert "The offer names a limit" ($null -ne $ccTerms.creditLimit)
    Assert "The offer names an APR" ($null -ne $ccTerms.apr)
    Write-Host "  Offered: $($ccTerms.cardTier) limit $($ccTerms.creditLimit) at $($ccTerms.apr)%"

    # A customer accepts the offer as made. There is no body, because there is
    # nothing about it for them to change.
    $ccAccepted = Post "$GW/api/applications/$CC_APP_ID/offer/accept" $null $TOKEN
    Assert "Customer accepts the card offer" ($ccAccepted -and $ccAccepted.status -eq "ACCEPTED")

    # Accepting twice must not issue a second card.
    $ccAgain = Post "$GW/api/applications/$CC_APP_ID/offer/accept" $null $TOKEN
    Assert "Accepting twice is idempotent" ($ccAgain -and $ccAgain.status -eq "ACCEPTED")

    $ccAfter = Get "$GW/api/applications/$CC_APP_ID" $TOKEN
    Assert "Application reached provisioning after acceptance" ($ccAfter.status -eq "PROVISIONING")

    # PROVISIONED is only reached when credit-card-service confirms the card
    # exists, and the application then holds the card's real id. Saying so on
    # the strength of having published a request is what produced 153 rows
    # claiming product_id = -1.
    $null = Wait-For "application confirmed as provisioned" {
        (Get "$GW/api/applications/$CC_APP_ID" $TOKEN).status -eq "PROVISIONED"
    } 180 3
    $ccDone = Get "$GW/api/applications/$CC_APP_ID" $TOKEN
    Assert "Application reached PROVISIONED once the card was confirmed" ($ccDone.status -eq "PROVISIONED")
    Assert "The application holds a real product id" ($ccDone.productId -gt 0)
}

# The card is created by a Kafka consumer, so this waits for the fact rather
# than for a duration.
# Wait for the fact, then read it back plainly. Taking the value straight out
# of Wait-For coupled the assertion to how PowerShell collects a function's
# output streams, which is not what this test is about.
$null = Wait-For "credit card created via Kafka" {
    (CountOf (Get "$GW/api/credit-cards/user/$USER_ID" $TOKEN)) -gt 0
} 180 3
$cards = @(Get "$GW/api/credit-cards/user/$USER_ID" $TOKEN | Where-Object { $null -ne $_ })
Assert "Credit card created via Kafka" ($cards.Count -gt 0)

if ($cards -and $cards.Count -gt 0) {
    $CARD_ID = $cards[0].id
    $card = $cards[0]
    Write-Host "  CardId=$CARD_ID  Limit=$($card.creditLimit)  APR=$($card.apr)%  Type=$($card.cardType)"
    Assert "Credit card status = ACTIVE" ($card.status -eq "ACTIVE")

    # Purchase $200
    # A purchase is simulated by staff. A customer views card transactions; they
    # do not invent them, and the refusal below is the assertion for that.
    Assert-Refused "A customer cannot manufacture a card purchase" "POST" `
        "$GW/api/credit-cards/$CARD_ID/purchase" $TOKEN 403 `
        @{ amount=5.00; description="Self-service spending"; merchantName="Harborline Groceries"; merchantCategory="GROCERIES" }
    $purchase = Post "$GW/api/credit-cards/$CARD_ID/purchase" @{ amount=200.00; description="Meridian Books purchase"; merchantName="Meridian Books"; merchantCategory="RETAIL" } $STAFF_TOKEN
    Assert "Credit card purchase $200" ($purchase -ne $null)

    $cardAfter = Get "$GW/api/credit-cards/$CARD_ID" $TOKEN
    $expectedCredit = $card.creditLimit - 200
    Assert "Available credit reduced by purchase" ($cardAfter.availableCredit -le $expectedCredit + 0.01)
    Write-Host "  AvailableCredit=$($cardAfter.availableCredit)  CurrentBalance=$($cardAfter.currentBalance)"

    # A payment must come out of an account. One that named none used to
    # restore the credit without debiting anything.
    Assert-Refused "A card payment with no funding account is refused" "POST" `
        "$GW/api/credit-cards/$CARD_ID/payment" $TOKEN 400 @{ amount=200.00 }

    # Make a payment from the checking account
    $payment = Post "$GW/api/credit-cards/$CARD_ID/payment" @{ amount=200.00; sourceAccountId=$ACCOUNT_ID } $TOKEN
    Assert "Credit card payment $200" ($payment -ne $null)

    $cardPaid = Get "$GW/api/credit-cards/$CARD_ID" $TOKEN
    Assert "Available credit restored after payment" ($cardPaid.availableCredit -ge $card.creditLimit - 200 - 0.01)
    Write-Host "  AvailableCredit after payment=$($cardPaid.availableCredit)"

    # Available credit is the limit less the balance: never more than the limit.
    Assert "Available credit is the limit less the balance" ([decimal]$cardPaid.availableCredit -eq [decimal]$cardPaid.creditLimit - [decimal]$cardPaid.currentBalance) "available=$($cardPaid.availableCredit) limit=$($cardPaid.creditLimit) balance=$($cardPaid.currentBalance)"

    # A statement's opening balance works back from its close. It used to add
    # purchases and subtract payments, the wrong way round.
    $stmt = Post "$GW/api/credit-cards/$CARD_ID/statements/generate" $null $STAFF_TOKEN
    if ($stmt) {
        $worked = [decimal]$stmt.closingBalance - [decimal]$stmt.totalPurchases - [decimal]$stmt.interestCharged - [decimal]$stmt.feesCharged + [decimal]$stmt.totalPayments
        Assert "The statement opens at its close less charges plus payments" ([decimal]$stmt.openingBalance -eq $worked) "opening=$($stmt.openingBalance) expected=$worked"
    } else {
        Assert "Staff can cut a statement" $false
    }

    # Statements
    $stmts = Get "$GW/api/credit-cards/$CARD_ID/statements" $TOKEN
    Write-Host "  Statements count=$($stmts.Count)"
}

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 3: Loan Lifecycle ===" -ForegroundColor Cyan

# Apply for personal loan — auto-reject (score 0 < 600), then manually approve
$loanAppBody = @{ userId=$USER_ID; applicationType="PERSONAL_LOAN"; requestedAmount=10000.00; termMonths=48; currency="USD"; purpose="Home improvement"; annualIncome=90000.00; monthlyDebtObligations=450.00 }
$loanApp = Post "$GW/api/applications" $loanAppBody $TOKEN
Assert "Submit PERSONAL_LOAN application" ($loanApp -and $loanApp.id)
$LOAN_APP_ID = $loanApp.id

if ($loanApp.status -eq "REJECTED") {
    # Staff-only, as above.
    Assert-Refused "Customer cannot review their own loan application" "PUT" `
        "$GW/api/applications/$LOAN_APP_ID/review" $TOKEN 403 `
        @{ decision="APPROVE"; reviewerNotes="Manual E2E approval"; approvedAmount=10000.00 }
} else {
    Assert "Loan application reached an offer" ($loanApp.status -eq "OFFERED")

    $loanOffers = Get "$GW/api/applications/$LOAN_APP_ID/offers" $TOKEN
    Assert "A loan offer was made" ((CountOf $loanOffers) -gt 0)
    $loanTerms = @($loanOffers)[0]
    Write-Host "  Offered: $($loanTerms.approvedAmount) over $($loanTerms.termMonths) months at $($loanTerms.apr)%"

    # The term the customer asked for is the term they are offered. This is the
    # assertion that would have caught a downstream service choosing its own.
    Assert "The offered term is the requested term" ($loanTerms.termMonths -eq 48)
    Assert "The offer carries an estimated payment" ($null -ne $loanTerms.monthlyPayment)

    $loanAccepted = Post "$GW/api/applications/$LOAN_APP_ID/offer/accept" $null $TOKEN
    Assert "Customer accepts the loan offer" ($loanAccepted -and $loanAccepted.status -eq "ACCEPTED")

    # A declined offer cannot be accepted, and an accepted one cannot be declined.
    Assert-Refused "An accepted offer cannot then be declined" "POST" `
        "$GW/api/applications/$LOAN_APP_ID/offer/decline" $TOKEN 409

    $loanAfter = Get "$GW/api/applications/$LOAN_APP_ID" $TOKEN
    Assert "Loan application reached provisioning after acceptance" ($loanAfter.status -eq "PROVISIONING")

    $null = Wait-For "loan application confirmed as provisioned" {
        (Get "$GW/api/applications/$LOAN_APP_ID" $TOKEN).status -eq "PROVISIONED"
    } 180 3
    $loanDone = Get "$GW/api/applications/$LOAN_APP_ID" $TOKEN
    Assert "Loan application reached PROVISIONED once the loan was confirmed" ($loanDone.status -eq "PROVISIONED")
    Assert "The loan application holds a real product id" ($loanDone.productId -gt 0)
}

$null = Wait-For "loan created via Kafka" {
    (CountOf (Get "$GW/api/loans/user/$USER_ID" $TOKEN)) -gt 0
} 180 3
$loans = @(Get "$GW/api/loans/user/$USER_ID" $TOKEN | Where-Object { $null -ne $_ })
Assert "Loan created via Kafka" ($loans.Count -gt 0)

if ($loans -and $loans.Count -gt 0) {
    $LOAN_ID = $loans[0].id
    $loan = $loans[0]
    Write-Host "  LoanId=$LOAN_ID  Principal=$($loan.principal)  Rate=$($loan.interestRate)%  MonthlyPayment=$($loan.monthlyPayment)"
    Assert "Loan status = PENDING (not yet disbursed)" ($loan.status -eq "PENDING" -or $loan.status -eq "ACTIVE")
    # The accepted offer is the source of truth. A downstream service choosing
    # its own term is how a twelve-month request became a forty-eight month loan.
    Assert "The loan's term is the offered term" ($loan.termMonths -eq 48)
    Assert "The loan's principal is the offered amount" ([decimal]$loan.principal -eq 10000.00)

    # Disburse loan to checking account
    $disburse = Post "$GW/api/loans/$LOAN_ID/disburse" @{ disbursementAccountId=$ACCOUNT_ID } $TOKEN
    Assert "Loan disbursed to checking account" ($disburse -ne $null)

    $loanAfter = Get "$GW/api/loans/$LOAN_ID" $TOKEN
    Assert "Loan status = ACTIVE after disbursal" ($loanAfter.status -eq "ACTIVE")

    # Get amortization schedule
    $schedule = Get "$GW/api/loans/$LOAN_ID/schedule" $TOKEN
    Assert "Amortization schedule returned" ($schedule -and $schedule.Count -gt 0)
    Write-Host "  Schedule rows=$($schedule.Count)  First payment due=$($schedule[0].dueDate)"

    Assert-Refused "A loan repayment with no funding account is refused" "POST" `
        "$GW/api/loans/$LOAN_ID/repay" $TOKEN 400 @{ amount=$loan.monthlyPayment }
    Assert-Refused "A 0.01 payoff with no funding account cannot close the loan" "POST" `
        "$GW/api/loans/$LOAN_ID/payoff" $TOKEN 400 @{ amount=0.01 }

    # Make a regular repayment
    $repay = Post "$GW/api/loans/$LOAN_ID/repay" @{ amount=$loan.monthlyPayment; sourceAccountId=$ACCOUNT_ID } $TOKEN
    Assert "Loan repayment made" ($repay -ne $null)
    # A payment below the instalment used to leave it PARTIAL for good, and the
    # loan closed once none was PENDING -- with the principal still owed.
    $beforeTiny = Get "$GW/api/loans/$LOAN_ID" $TOKEN
    $tiny = Post "$GW/api/loans/$LOAN_ID/repay" @{ amount=0.01; sourceAccountId=$ACCOUNT_ID } $TOKEN
    $afterTiny = Get "$GW/api/loans/$LOAN_ID" $TOKEN
    Assert "A 0.01 repayment leaves the loan open with its principal owed" ($tiny -and $afterTiny.status -eq "ACTIVE" -and [decimal]$afterTiny.remainingBalance -eq [decimal]$beforeTiny.remainingBalance) "status=$($afterTiny.status) balance=$($afterTiny.remainingBalance)"
    # The console offers "the rest of this instalment", which needs what has
    # been paid towards it; the schedule now says.
    $partRow = (Get "$GW/api/loans/$LOAN_ID/schedule" $TOKEN) | Where-Object { $_.status -eq "PARTIAL" } | Select-Object -First 1
    Assert "A part-paid instalment reports what has been paid on it" ($partRow -and [decimal]$partRow.amountPaid -eq 0.01) "amountPaid=$($partRow.amountPaid)"

    # Get payoff quote
    $quote = Get "$GW/api/loans/$LOAN_ID/payoff-quote" $TOKEN
    Assert "Payoff quote returned" ($quote -ne $null)
    Write-Host "  Payoff amount=$($quote.totalPayoffAmount)"
}

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 5: Scheduled Payment ===" -ForegroundColor Cyan

# Create beneficiary first
$ben = Post "$GW/api/payments/beneficiaries" @{ userId=$USER_ID; name="E2E Landlord"; nickname="Landlord"; accountNumber="9876543210"; bankName="Example Bank"; routingNumber="990000001"; beneficiaryType="EXTERNAL_ACH"; currency="USD" } $TOKEN
Assert "Create beneficiary" ($ben -and $ben.id)
# The payee's external account number leaves the service masked.
Assert "A saved payee's account number is returned masked" ($ben -and "$($ben.accountNumber)" -ne "9876543210" -and "$($ben.accountNumber)".EndsWith("3210")) "accountNumber=$($ben.accountNumber)"
# A payment outside Northbank has to name who receives it.
Assert-Refused "An external payment naming no payee is refused" "POST" `
    "$GW/api/payments" $TOKEN 422 @{ payerAccountId=$ACCOUNT_ID; paymentType="WIRE"; amount=5.00; currency="USD"; description="To nobody" }
$BEN_ID = $ben.id

# Schedule a recurring payment for tomorrow
$tomorrow = (Get-Date).AddDays(1).ToString("yyyy-MM-ddTHH:mm:ss")
$schedPay = Post "$GW/api/payments" @{ payerAccountId=$ACCOUNT_ID; beneficiaryId=$BEN_ID; paymentType="EXTERNAL_ACH"; amount=50.00; currency="USD"; description="Monthly rent"; recurring=$true; recurrencePattern="MONTHLY"; scheduledAt=$tomorrow } $TOKEN
Assert "Create scheduled recurring payment" ($schedPay -and $schedPay.id)
Assert "Payment status = PENDING" ($schedPay.status -eq "PENDING")
Assert "isRecurring = true" ($schedPay.recurring -eq $true)
Write-Host "  PaymentId=$($schedPay.id)  ScheduledAt=$($schedPay.scheduledAt)  Pattern=$($schedPay.recurrencePattern)"

$upcoming = Get "$GW/api/payments/account/$ACCOUNT_ID/scheduled" $TOKEN
Assert "Upcoming scheduled payments visible" ($upcoming -ne $null)

# A payment to an outside payee debits the payer. It used to be marked
# COMPLETED without any money leaving the account. The retry reuses the key,
# so a lost response cannot send the money twice.
$extBefore = [decimal](Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN).balance
$payKey = "e2e-pay-$([guid]::NewGuid())"
$payHeaders = @{ "Content-Type" = "application/json"; "Authorization" = "Bearer $TOKEN"; "Idempotency-Key" = $payKey }
$payBody = @{ payerAccountId=$ACCOUNT_ID; beneficiaryId=$BEN_ID; paymentType="EXTERNAL_ACH"; amount=12.34; currency="USD"; description="Utility bill" } | ConvertTo-Json
$extPay = $null; $extReplay = $null
try { $extPay = Invoke-RestMethod "$GW/api/payments" -Method POST -Body $payBody -Headers $payHeaders -TimeoutSec $script:WRITE_TIMEOUT } catch { Write-Host "    POST payment => $($_.Exception.Message)" -ForegroundColor DarkYellow }
try { $extReplay = Invoke-RestMethod "$GW/api/payments" -Method POST -Body $payBody -Headers $payHeaders -TimeoutSec $script:WRITE_TIMEOUT } catch { Write-Host "    POST payment replay => $($_.Exception.Message)" -ForegroundColor DarkYellow }
Assert "An outside payment completes" ($extPay -and $extPay.status -eq "COMPLETED") "status=$($extPay.status)"
Assert "Retrying with the same key returns the same payment" ($extReplay -and $extReplay.id -eq $extPay.id)
Assert "The payer was debited once for the outside payment" ([decimal](Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN).balance -eq $extBefore - 12.34)

# A recurring payment made now still recurs: its next occurrence is waiting,
# a month out. Only the scheduler used to create one.
$recurNow = Post "$GW/api/payments" @{ payerAccountId=$ACCOUNT_ID; beneficiaryId=$BEN_ID; paymentType="EXTERNAL_ACH"; amount=1.00; currency="USD"; description="Streaming subscription"; recurring=$true; recurrencePattern="MONTHLY" } $TOKEN
Assert "A recurring payment made now completes" ($recurNow -and $recurNow.status -eq "COMPLETED") "status=$($recurNow.status)"
# Enumerated first: Windows PowerShell hands a JSON array down the pipeline as one object.
$nextUp = @((Get "$GW/api/payments/account/$ACCOUNT_ID/scheduled" $TOKEN) | ForEach-Object { $_ } | Where-Object { $_.description -eq "Streaming subscription" })
$expectedNext = (Get-Date).ToUniversalTime().AddMonths(1).ToString("yyyy-MM-dd")
Assert "Its next payment is scheduled a month out" ($nextUp.Count -eq 1 -and "$($nextUp[0].scheduledAt)".StartsWith($expectedNext)) "found=$($nextUp.Count) at=$($nextUp[0].scheduledAt) expected=$expectedNext"
if ($nextUp.Count -ge 1) { $null = Put "$GW/api/payments/$($nextUp[0].id)/cancel" $null $TOKEN }

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 6: Wire Transfer ===" -ForegroundColor Cyan

$wire = Post "$GW/api/integrations/wire-transfer" @{ fromAccountId=$ACCOUNT_ID; beneficiaryName="London Corp Ltd"; beneficiaryAccount="GB29EXMP60161331926819"; swiftCode="EXMPGB2L"; bankName="Example Bank"; bankCountry="GB"; amount=5000.00; currency="USD"; purpose="Business payment" } $TOKEN
Assert "WIRE transfer initiated" ($wire -and $wire.transferRef)
Assert "Status = PENDING" ($wire.status -eq "PENDING")
# The services run in UTC and this script runs in the machine's local zone, so
# either side of midnight the two disagree about what "today" is. Both readings
# of T+2 are accepted; T+3 still fails.
Assert "Estimated arrival = T+2" (@(
        (Get-Date).AddDays(2).ToString("yyyy-MM-dd"),
        (Get-Date).ToUniversalTime().AddDays(2).ToString("yyyy-MM-dd")
    ) -contains $wire.estimatedArrival) "got $($wire.estimatedArrival)"
Write-Host "  TransferRef=$($wire.transferRef)  EstimatedArrival=$($wire.estimatedArrival)"

if ($wire -and $wire.transferRef) {
    $transferGet = Get "$GW/api/integrations/transfer/$($wire.transferRef)" $TOKEN
    Assert "GET transfer by ref" ($transferGet -and $transferGet.transferRef -eq $wire.transferRef)
} else {
    Assert "GET transfer by ref" $false "skipped - wire transfer failed"
}

# A second customer, registered only to be refused. The outward rails move
# money out of the bank, so the source account decides who may use them, and a
# transfer reference is a guessable handle to someone else's beneficiary and
# amount. Both are asserted through the real gateway rather than a mock.
$otherReg = @{
    username      = "e2eother$ts"
    email         = "e2eother$ts@example.com"
    password      = "Test1234!"
    firstName     = "E2E"
    lastName      = "Other"
    dateOfBirth   = "1991-02-20"
    phone         = "2405550149"
    streetAddress = "456 Example Street"
    city          = "Silver Spring"
    state         = "MD"
    postalCode    = "20910"
    ssn           = "123-45-6780"
}
$otherAuth = Post "$GW/api/auth/register" $otherReg
Assert "Register a second customer" ($otherAuth -and $otherAuth.token)
$OTHER_TOKEN = $otherAuth.token

Assert-Refused "Another customer cannot wire from an account they do not own" "POST" `
    "$GW/api/integrations/wire-transfer" $OTHER_TOKEN 403 `
    @{ fromAccountId=$ACCOUNT_ID; beneficiaryName="Mallory"; beneficiaryAccount="GB29EXMP60161331926819"; swiftCode="EXMPGB2L"; bankName="Example Bank"; bankCountry="GB"; amount=100.00; currency="USD"; purpose="Not theirs" }

# External transfers are validated before anything is recorded. There were
# no constraints: a negative amount was stored and announced.
Assert-Refused "A wire with a negative amount is refused" "POST" `
    "$GW/api/integrations/wire-transfer" $TOKEN 400 `
    @{ fromAccountId=$ACCOUNT_ID; beneficiaryName="London Corp Ltd"; beneficiaryAccount="GB29EXMP60161331926819"; bankName="Example Bank"; bankCountry="GB"; amount=-50.00; currency="USD"; purpose="Negative" }
Assert-Refused "An ACH transfer without a valid routing number is refused" "POST" `
    "$GW/api/integrations/ach-transfer" $TOKEN 400 `
    @{ fromAccountId=$ACCOUNT_ID; beneficiaryName="Acme Payroll"; beneficiaryAccount="12345678"; routingNumber="12AB"; bankName="Acme Bank"; amount=10.00; currency="USD" }

Assert-Refused "Another customer cannot ACH from an account they do not own" "POST" `
    "$GW/api/integrations/ach-transfer" $OTHER_TOKEN 403 `
    @{ fromAccountId=$ACCOUNT_ID; beneficiaryName="Mallory"; beneficiaryAccount="12345678"; routingNumber="990000002"; bankName="Acme Bank"; amount=100.00; currency="USD"; purpose="Not theirs" }

if ($wire -and $wire.transferRef) {
    Assert-Refused "Another customer cannot read someone else's transfer by reference" "GET" `
        "$GW/api/integrations/transfer/$($wire.transferRef)" $OTHER_TOKEN 403
} else {
    # Without an else the assertion would silently disappear when the wire
    # transfer failed, and the suite would report a smaller total, all green.
    # A check that can vanish is not a check.
    Assert "Another customer cannot read someone else's transfer by reference" $false "skipped - wire transfer failed"
}

# Second-factor management is self-only: no customer, and no role, manages
# another account's authenticator.
# ─────────────────────────────────────────────────────────────────────────────
# Staff review: the decision record a reviewer needs, and that the record is the
# bank's working rather than the customer's view.
if ($STAFF_TOKEN -and $CC_APP_ID) {
    $decisions = Get "$GW/api/applications/$CC_APP_ID/decisions" $STAFF_TOKEN
    Assert "A reviewer can read the decision record" ((CountOf $decisions) -gt 0)
    $d = @($decisions)[0]
    Assert "The decision names the policy it was taken under" ($null -ne $d.policyVersion)
    Assert "The decision records the score it was taken on" ($null -ne $d.creditScoreAtDecision)
    Assert "The decision carries machine-readable reasons" ($null -ne $d.reasonCodes)
    Write-Host "  Decision: $($d.decision) under policy $($d.policyVersion)"

    Assert-Refused "A customer cannot read the decision record" "GET" `
        "$GW/api/applications/$CC_APP_ID/decisions" $TOKEN 403
}

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 3b: Decline, manual review and offer boundaries ===" -ForegroundColor Cyan

# A second offer on the verified customer, to decline. The first two were
# accepted; the decline path is its own lifecycle and needs its own proof.
$declApp = Post "$GW/api/applications" @{ userId=$USER_ID; applicationType="PERSONAL_LOAN"; requestedAmount=5000.00; termMonths=24; currency="USD"; purpose="Decline journey"; annualIncome=90000.00; monthlyDebtObligations=450.00 } $TOKEN
Assert "Submit a loan application to decline" ($declApp -and $declApp.status -eq "OFFERED") "status=$($declApp.status)"
$DECL_APP_ID = $declApp.id

if ($DECL_APP_ID -and $OTHER_TOKEN) {
    # Another customer is refused on every offer route, by the stored owner of
    # the application rather than by anything in the request.
    Assert-Refused "Another customer cannot read this offer" "GET" `
        "$GW/api/applications/$DECL_APP_ID/offers" $OTHER_TOKEN 403
    Assert-Refused "Another customer cannot accept this offer" "POST" `
        "$GW/api/applications/$DECL_APP_ID/offer/accept" $OTHER_TOKEN 403
    Assert-Refused "Another customer cannot decline this offer" "POST" `
        "$GW/api/applications/$DECL_APP_ID/offer/decline" $OTHER_TOKEN 403
}

if ($DECL_APP_ID) {
    $loansBefore = CountOf (Get "$GW/api/loans/user/$USER_ID" $TOKEN)
    $declined = Post "$GW/api/applications/$DECL_APP_ID/offer/decline" $null $TOKEN
    Assert "Customer declines the offer" ($declined -and $declined.status -eq "DECLINED")
    $declAfter = Get "$GW/api/applications/$DECL_APP_ID" $TOKEN
    Assert "A declined application is DECLINED" ($declAfter.status -eq "DECLINED") "status=$($declAfter.status)"
    Assert "A declined application names no product" ($null -eq $declAfter.productId)
    Assert-Refused "A declined offer cannot then be accepted" "POST" `
        "$GW/api/applications/$DECL_APP_ID/offer/accept" $TOKEN 409
    if ($STAFF_TOKEN) {
        Assert-Refused "Staff cannot accept a declined customer offer either" "POST" `
            "$GW/api/applications/$DECL_APP_ID/offer/accept" $STAFF_TOKEN 403
    }
    # Nothing was published, so nothing can arrive. Give a consumer the time it
    # would have taken anyway before counting.
    Start-Sleep -Seconds 5
    Assert "Declining provisioned no loan" ((CountOf (Get "$GW/api/loans/user/$USER_ID" $TOKEN)) -eq $loansBefore)
}

# The second customer has not finished an identity check, so policy refers
# their credit application to a person. That referral is the real route into
# MANUAL_REVIEW, not a state forced for the test.
if ($OTHER_TOKEN -and $STAFF_TOKEN) {
    $OTHER_ID = $otherAuth.userId
    $mrApp = Post "$GW/api/applications" @{ userId=$OTHER_ID; applicationType="CREDIT_CARD"; currency="USD"; purpose="Manual review journey"; annualIncome=90000.00; monthlyDebtObligations=450.00 } $OTHER_TOKEN
    Assert "An unverified customer's credit application is referred" ($mrApp -and $mrApp.status -eq "MANUAL_REVIEW") "status=$($mrApp.status)"
    $MR_APP_ID = $mrApp.id

    if ($MR_APP_ID) {
        Assert-Refused "The applicant cannot resolve their own referral" "PUT" `
            "$GW/api/applications/$MR_APP_ID/review" $OTHER_TOKEN 403 `
            @{ decision="APPROVE"; reviewerNotes="Self-approval" }

        $mrDecisions = @(Get "$GW/api/applications/$MR_APP_ID/decisions" $STAFF_TOKEN)
        Assert "A reviewer sees why it was referred" (($mrDecisions | ForEach-Object { $_.reasonCodes }) -contains "KYC_REVIEW_REQUIRED")

        $mrApproved = Put "$GW/api/applications/$MR_APP_ID/review" @{ decision="APPROVE"; reviewerNotes="Identity documents seen in branch" } $STAFF_TOKEN
        Assert "A reviewer's approval becomes an offer" ($mrApproved -and $mrApproved.status -eq "OFFERED") "status=$($mrApproved.status)"
        Assert "A reviewer's approval creates no product" ($null -eq $mrApproved.productId)
        # The note was written for colleagues; the applicant's own view of the
        # application leaves it out, and staff still read it.
        Assert "The applicant does not see the reviewer's notes" ($null -eq (Get "$GW/api/applications/$MR_APP_ID" $OTHER_TOKEN).reviewerNotes)
        Assert "Staff still see the reviewer's notes" ((Get "$GW/api/applications/$MR_APP_ID" $STAFF_TOKEN).reviewerNotes -eq "Identity documents seen in branch")
        Assert "The approval produced offer terms" ((CountOf (Get "$GW/api/applications/$MR_APP_ID/offers" $OTHER_TOKEN)) -gt 0)
        Start-Sleep -Seconds 5
        Assert "No card exists until the customer accepts" ((CountOf (Get "$GW/api/credit-cards/user/$OTHER_ID" $OTHER_TOKEN)) -eq 0)

        # Staff decided to make the offer; answering it is the applicant's
        # decision, and there is no assisted-service workflow to act for them.
        Assert-Refused "The reviewer cannot accept the customer's offer" "POST" `
            "$GW/api/applications/$MR_APP_ID/offer/accept" $STAFF_TOKEN 403
        Assert-Refused "The reviewer cannot decline the customer's offer" "POST" `
            "$GW/api/applications/$MR_APP_ID/offer/decline" $STAFF_TOKEN 403
        $mrAccepted = Post "$GW/api/applications/$MR_APP_ID/offer/accept" $null $OTHER_TOKEN
        Assert "The applicant accepts the reviewed offer" ($mrAccepted -and $mrAccepted.status -eq "ACCEPTED") "status=$($mrAccepted.status)"
        $null = Wait-For "reviewed card confirmed as provisioned" {
            (Get "$GW/api/applications/$MR_APP_ID" $OTHER_TOKEN).status -eq "PROVISIONED"
        } 180 3
        $mrDone = Get "$GW/api/applications/$MR_APP_ID" $OTHER_TOKEN
        Assert "The reviewed application reaches PROVISIONED with a real product id" ($mrDone.status -eq "PROVISIONED" -and $mrDone.productId -gt 0) "status=$($mrDone.status)"
    }

    $rjApp = Post "$GW/api/applications" @{ userId=$OTHER_ID; applicationType="PERSONAL_LOAN"; requestedAmount=3000.00; termMonths=12; currency="USD"; purpose="Rejection journey"; annualIncome=90000.00; monthlyDebtObligations=450.00 } $OTHER_TOKEN
    Assert "A second referral for rejection" ($rjApp -and $rjApp.status -eq "MANUAL_REVIEW") "status=$($rjApp.status)"
    if ($rjApp.id) {
        $rejected = Put "$GW/api/applications/$($rjApp.id)/review" @{ decision="REJECT"; reviewerNotes="Could not verify identity" } $STAFF_TOKEN
        Assert "A reviewer's rejection is REJECTED" ($rejected -and $rejected.status -eq "REJECTED") "status=$($rejected.status)"
        Assert "A rejection makes no offer" ((CountOf (Get "$GW/api/applications/$($rjApp.id)/offers" $OTHER_TOKEN)) -eq 0)
        $acceptRejected = Status "POST" "$GW/api/applications/$($rjApp.id)/offer/accept" $OTHER_TOKEN
        Assert "Nothing can be accepted on a rejected application" ($acceptRejected -in 404, 409) "got HTTP $acceptRejected"
        Assert "A rejection provisions no loan" ((CountOf (Get "$GW/api/loans/user/$OTHER_ID" $OTHER_TOKEN)) -eq 0)
    }
}

# Card status authority: a cardholder may take precautions and undo them, and
# may not touch anything the bank applied. The service used to assign whichever
# status arrived in the request body, so a customer could mark their own card
# defaulted, or clear a default and carry on spending.
if ($CARD_ID) {
    $frozen = Put "$GW/api/credit-cards/$CARD_ID/status" @{ status="CUSTOMER_FROZEN" } $TOKEN
    Assert "A customer may freeze their own card" ($frozen -and $frozen.status -eq "CUSTOMER_FROZEN")

    # Simulated by staff, because a customer cannot make a purchase at all
    # now. Sent as the customer this would be refused for the wrong reason:
    # 403 for who is asking, rather than 422 for the card being frozen.
    Assert-Refused "A frozen card cannot be spent on" "POST" `
        "$GW/api/credit-cards/$CARD_ID/purchase" $STAFF_TOKEN 422 `
        @{ amount=10.00; description="While frozen"; merchantName="Harborline Groceries"; merchantCategory="GROCERIES" }

    $thawed = Put "$GW/api/credit-cards/$CARD_ID/status" @{ status="ACTIVE" } $TOKEN
    Assert "A customer may lift their own freeze" ($thawed -and $thawed.status -eq "ACTIVE")

    Assert-Refused "A customer cannot mark their own card defaulted" "PUT" `
        "$GW/api/credit-cards/$CARD_ID/status" $TOKEN 403 @{ status="DEFAULTED" }

    Assert-Refused "A customer cannot block their own card as the bank would" "PUT" `
        "$GW/api/credit-cards/$CARD_ID/status" $TOKEN 403 @{ status="SYSTEM_BLOCKED" }

    Assert-Refused "A customer cannot close their own card by setting the state" "PUT" `
        "$GW/api/credit-cards/$CARD_ID/status" $TOKEN 403 @{ status="CLOSED" }

    Assert-Refused "A customer cannot cut their own statement" "POST" `
        "$GW/api/credit-cards/$CARD_ID/statements/generate" $TOKEN 403
}

# ─────────────────────────────────────────────────────────────────────────────
# Cross-resource ownership: both sides of a money movement, not just one.
#
# Owning a loan is authority over the loan. It is not authority over the account
# the repayment comes out of. Every write used to check the first and not the
# second, so a customer could settle their own debt out of an account they had
# guessed the id of — the victim's balance went down and the attacker's debt
# went down with it.
$otherAppBody = @{ userId=$otherAuth.userId; applicationType="CHECKING_ACCOUNT"; currency="USD"; purpose="Second customer checking" }
$otherApp = Post "$GW/api/applications" $otherAppBody $OTHER_TOKEN
$VICTIM_ACCOUNT_ID = $otherApp.productId
if (-not $VICTIM_ACCOUNT_ID) {
    $otherAccounts = Get "$GW/api/accounts/user/$($otherAuth.userId)" $OTHER_TOKEN
    if ($otherAccounts -and (CountOf $otherAccounts) -gt 0) { $VICTIM_ACCOUNT_ID = @($otherAccounts)[0].id }
}
Assert "A second customer has an account of their own" ($VICTIM_ACCOUNT_ID -and $VICTIM_ACCOUNT_ID -gt 0)

# A scheduled internal payment, executed by the scheduler rather than by the
# request. The job has no caller of its own; it used to reach
# transaction-service anonymous, be refused, and mark every due payment FAILED.
# Due in the server's clock (UTC) shortly, so the request only schedules it.
if ($VICTIM_ACCOUNT_ID) {
    $payerBefore = [decimal](Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN).balance
    $payeeBefore = [decimal](Get "$GW/api/accounts/$VICTIM_ACCOUNT_ID" $OTHER_TOKEN).balance
    $dueSoon = (Get-Date).ToUniversalTime().AddSeconds(20).ToString("yyyy-MM-ddTHH:mm:ss")
    $internalPay = Post "$GW/api/payments" @{ payerAccountId=$ACCOUNT_ID; payeeAccountId=$VICTIM_ACCOUNT_ID; paymentType="INTERNAL"; amount=5.00; currency="USD"; description="Scheduled internal payment"; scheduledAt=$dueSoon } $TOKEN
    Assert "A scheduled internal payment is accepted and waits" ($internalPay -and $internalPay.status -eq "PENDING") "status=$($internalPay.status)"
    if ($internalPay.id) {
        $null = Wait-For "scheduler executed the internal payment" {
            (Get "$GW/api/payments/$($internalPay.id)" $TOKEN).status -in "COMPLETED", "FAILED"
        } 240 5
        $ran = Get "$GW/api/payments/$($internalPay.id)" $TOKEN
        Assert "The scheduler completed the internal payment" ($ran.status -eq "COMPLETED") "status=$($ran.status)"
        Assert "The payer was debited once" ([decimal](Get "$GW/api/accounts/$ACCOUNT_ID" $TOKEN).balance -eq $payerBefore - 5.00)
        Assert "The payee was credited once" ([decimal](Get "$GW/api/accounts/$VICTIM_ACCOUNT_ID" $OTHER_TOKEN).balance -eq $payeeBefore + 5.00)
    }

    $later = (Get-Date).ToUniversalTime().AddDays(3).ToString("yyyy-MM-ddTHH:mm:ss")
    $toCancel = Post "$GW/api/payments" @{ payerAccountId=$ACCOUNT_ID; payeeAccountId=$VICTIM_ACCOUNT_ID; paymentType="INTERNAL"; amount=5.00; currency="USD"; description="To be cancelled"; scheduledAt=$later } $TOKEN
    $cancelled = Put "$GW/api/payments/$($toCancel.id)/cancel" $null $TOKEN
    Assert "A pending scheduled payment can be cancelled" ($cancelled -and $cancelled.status -eq "CANCELLED") "status=$($cancelled.status)"
}

# Repaying a loan or paying a card moves the customer's money; staff may read
# both but not act on the customer's behalf.
if ($STAFF_TOKEN -and $LOAN_ID) {
    Assert-Refused "Staff cannot repay a customer's loan from the customer's account" "POST" `
        "$GW/api/loans/$LOAN_ID/repay" $STAFF_TOKEN 403 @{ amount=10.00; sourceAccountId=$ACCOUNT_ID }
}
if ($STAFF_TOKEN -and $CARD_ID) {
    Assert-Refused "Staff cannot pay a customer's card from the customer's account" "POST" `
        "$GW/api/credit-cards/$CARD_ID/payment" $STAFF_TOKEN 403 @{ amount=10.00; sourceAccountId=$ACCOUNT_ID }
}

if ($VICTIM_ACCOUNT_ID -and $LOAN_ID) {
    Assert-Refused "A loan cannot be repaid from someone else's account" "POST" `
        "$GW/api/loans/$LOAN_ID/repay" $TOKEN 403 `
        @{ amount=10.00; sourceAccountId=$VICTIM_ACCOUNT_ID }

    Assert-Refused "A loan cannot be paid off from someone else's account" "POST" `
        "$GW/api/loans/$LOAN_ID/payoff" $TOKEN 403 `
        @{ amount=10.00; sourceAccountId=$VICTIM_ACCOUNT_ID }
}

if ($VICTIM_ACCOUNT_ID -and $CARD_ID) {
    Assert-Refused "A card cannot be paid from someone else's account" "POST" `
        "$GW/api/credit-cards/$CARD_ID/payment" $TOKEN 403 `
        @{ amount=10.00; sourceAccountId=$VICTIM_ACCOUNT_ID }

    Assert-Refused "A cash advance cannot be paid into someone else's account" "POST" `
        "$GW/api/credit-cards/$CARD_ID/cash-advance" $TOKEN 403 `
        @{ amount=10.00; targetAccountId=$VICTIM_ACCOUNT_ID }
}

Assert-Refused "Another customer cannot start 2FA enrolment on someone else's account" "POST" `
    "$GW/api/auth/2fa/setup?userId=$USER_ID" $OTHER_TOKEN 403

Assert-Refused "Another customer cannot disable someone else's 2FA" "DELETE" `
    "$GW/api/auth/2fa?userId=$USER_ID" $OTHER_TOKEN 403 @{ code="123456" }

$fxRate = Get "$GW/api/integrations/exchange-rate?from=USD&to=GBP" $TOKEN
Assert "FX rate USD->GBP returned" ($fxRate -and $fxRate.rate -gt 0)
Write-Host "  USD->GBP rate=$($fxRate.rate)"

# SWIFT transfer
$swift = Post "$GW/api/integrations/swift-transfer" @{ fromAccountId=$ACCOUNT_ID; beneficiaryName="Harbour Partners"; iban="JP1234567890"; swiftCode="EXMPJPJT"; bankName="Example Bank Japan"; bankCountry="JP"; amount=2000.00; currency="USD"; purpose="Services" } $TOKEN
Assert "SWIFT transfer initiated" ($swift -and $swift.transferType -eq "SWIFT")
Assert "SWIFT estimated arrival = T+5" (@(
        (Get-Date).AddDays(5).ToString("yyyy-MM-dd"),
        (Get-Date).ToUniversalTime().AddDays(5).ToString("yyyy-MM-dd")
    ) -contains $swift.estimatedArrival) "got $($swift.estimatedArrival)"

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 7: Platform Statistics ===" -ForegroundColor Cyan

# Platform-wide and daily statistics are staff-only. A customer token must be
# refused, and that refusal is the assertion — not a red line in the output.
Assert-Refused "Customer cannot read platform statistics" "GET" `
    "$GW/api/statistics/platform" $TOKEN 403

$today = Get-Date -Format "yyyy-MM-dd"
Assert-Refused "Customer cannot read the daily snapshot" "GET" `
    "$GW/api/statistics/daily?date=$today" $TOKEN 403

# Their own statistics are theirs to read.
$userStats = Get "$GW/api/statistics/users/$USER_ID" $TOKEN
Assert "Customer reads their own statistics" ($userStats -ne $null)

# And nobody else's.
Assert-Refused "Customer cannot read another customer's statistics" "GET" `
    "$GW/api/statistics/users/999999" $TOKEN 403

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 8: Notifications ===" -ForegroundColor Cyan

$notifs = Get "$GW/api/notifications/user/$USER_ID" $TOKEN
Assert "Notifications returned" ($notifs -ne $null)
Write-Host "  UnreadCount=$($notifs.unreadCount)  Total=$($notifs.notifications.Count)"

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 9: KYC state after review ===" -ForegroundColor Cyan

# The documents were submitted and the check completed above, before any credit
# application, because credit will not proceed without it. What is left to
# assert here is that the settled state is what the customer reads back.
$userAfterKyc = Get "$GW/api/users/$USER_ID" $TOKEN
Assert "User KYC status = APPROVED after review" ($userAfterKyc.kycStatus -eq "APPROVED")
Write-Host "  KycStatus=$($userAfterKyc.kycStatus)"

$docsAfter = Get "$GW/api/users/$USER_ID/kyc/documents" $TOKEN
Assert "The submitted documents are still listed" ((CountOf $docsAfter) -ge 2)

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 10: Credit Score ===" -ForegroundColor Cyan

# The loan repayment in Flow 3 publishes LOAN_REPAYMENT_MADE, and user-service
# raises the score by 5 when it consumes it. That reward had never been given:
# the event carried no userId and the consumer returned on the null. It is
# waited for rather than assumed, because a Kafka consumer settles when it
# settles, and asserted unconditionally so a regression turns this red instead
# of quietly removing an assertion from the total.
$scoreRose = Wait-For "credit score raised by the loan repayment event" {
    $s = Get "$GW/api/users/$USER_ID/credit-score" $TOKEN
    $s -and $s.score -gt 700
}
Assert "Score increased via Kafka (loan repayment event processed)" $scoreRose

$score = Get "$GW/api/users/$USER_ID/credit-score" $TOKEN
Assert "Credit score returned" ($score -ne $null)
Assert "Score is in valid range (300-850)" ($score.score -ge 300 -and $score.score -le 850)
Assert "Rating field present" ($score.rating -ne $null)
Write-Host "  Score=$($score.score)  Rating=$($score.rating)"

# An empty history is a valid answer for a customer who has just registered, so
# the assertion is that the endpoint answers rather than that it returns rows.
# `$empty -ne $null` is falsy in PowerShell, which is what made this red.
$history = @(Get "$GW/api/users/$USER_ID/credit-score/history" $TOKEN | Where-Object { $null -ne $_ })
Assert "Credit score history is readable" `
    ((Status "GET" "$GW/api/users/$USER_ID/credit-score/history" $TOKEN) -eq 200)
Write-Host "  History entries=$($history.Count)"

# The repayment reward above guarantees at least one history row, so these are
# asserted rather than skipped when the list happens to be empty.
Assert "Credit score history has at least one entry" ($history.Count -gt 0)
if ($history -and $history.Count -gt 0) {
    $latest = $history[0]
    Assert "History entry has delta" ($latest.delta -ne $null)
    Assert "History entry has reason" ($latest.changeReason -ne $null)
    # No ternary, and no bare ">": both are PowerShell 7 syntax, and ">" is a
    # redirection operator in 5.1 rather than a comparison. The script would not
    # parse at all, so nothing below this line ran.
    $sign = if ($latest.delta -gt 0) { "+" } else { "" }
    Write-Host "  Latest change: $sign$($latest.delta)  Reason=$($latest.changeReason)"
}

Write-Host "  Kafka credit score update confirmed: $($score.score) > 700"

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== FLOW 11: 2FA Setup + Verify + Disable ===" -ForegroundColor Cyan

# Setup — generate secret
$setup = Post "$GW/api/auth/2fa/setup?userId=$USER_ID" $null $TOKEN
Assert "2FA setup returns secret" ($setup -and $setup.secret)
Assert "2FA setup returns otpauthUri" ($setup.otpauthUri -like "otpauth://totp/*")
Write-Host "  Secret length=$($setup.secret.Length)  Uri=$($setup.otpauthUri.Substring(0, 40))..."

if ($setup -and $setup.secret) {
    $SECRET = $setup.secret

    # Compute TOTP code from secret
    $totpCode = Get-TOTP $SECRET
    # The code itself is not printed: a test log is not a place to practise
    # writing down one-time codes, synthetic or otherwise.
    Write-Host "  Computed a TOTP code from the enrolment secret"

    # Verify + enable
    $verifyResult = Post "$GW/api/auth/2fa/verify?userId=$USER_ID" @{ code=$totpCode } $TOKEN
    Assert "2FA verify+enable succeeds" ($verifyResult -ne $null -and $verifyResult.message -like "*enabled*")

    # Confirm twoFactorEnabled = true on user
    $userWith2fa = Get "$GW/api/users/$USER_ID" $TOKEN
    Assert "User twoFactorEnabled = true" ($userWith2fa.twoFactorEnabled -eq $true)
    Write-Host "  twoFactorEnabled=$($userWith2fa.twoFactorEnabled)"

    # Wait for next TOTP window to avoid same-code reuse (optional — verifier allows ±1 window)
    Start-Sleep -Seconds 2

    # Disable — requires valid TOTP code
    $totpCode2 = Get-TOTP $SECRET
    $disableResult = Delete "$GW/api/auth/2fa?userId=$USER_ID" @{ code=$totpCode2 } $TOKEN
    Assert "2FA disable succeeds" ($disableResult -ne $null -and $disableResult.message -like "*disabled*")

    # Confirm twoFactorEnabled = false
    $userNo2fa = Get "$GW/api/users/$USER_ID" $TOKEN
    Assert "User twoFactorEnabled = false after disable" ($userNo2fa.twoFactorEnabled -eq $false)
    Write-Host "  twoFactorEnabled=$($userNo2fa.twoFactorEnabled)"
}

# ─────────────────────────────────────────────────────────────────────────────
Write-Host "`n=== RESULTS ===" -ForegroundColor Cyan
Write-Host "  PASSED: $PASS" -ForegroundColor Green
Write-Host "  FAILED: $FAIL" -ForegroundColor $(if ($FAIL -eq 0) { "Green" } else { "Red" })
Write-Host "  TOTAL:  $($PASS + $FAIL)`n"

# A suite that prints FAIL and exits 0 is not a suite anything can gate on.
if ($FAIL -gt 0) { exit 1 }
exit 0
