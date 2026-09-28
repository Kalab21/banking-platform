import { randomUUID } from "node:crypto";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * Servicing a loan and a card from the console, against a running stack.
 *
 * Receive a loan's money, pay an instalment, pay the loan off, and pay a card —
 * each through the page, each checked against what the gateway then reports
 * for the loan, the card and the account the money moved through.
 *
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     E2E_USERNAME=... E2E_PASSWORD=... \
 *     npx playwright test --project=live servicing
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const USERNAME = process.env.E2E_USERNAME ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "DemoPassword123!";
const STAFF_USERNAME = process.env.NORTHBANK_DEMO_STAFF_USERNAME ?? "northbank.reviewer";
const STAFF_PASSWORD = process.env.NORTHBANK_DEMO_STAFF_PASSWORD ?? "ReviewerDemo2026!";

test.skip(!USERNAME, "Set E2E_USERNAME and E2E_PASSWORD from scripts/seed-demo.sh output.");
test.describe.configure({ mode: "serial", timeout: 300_000 });

interface Session {
  userId: number;
  headers: { Authorization: string };
}

async function login(request: APIRequestContext, username: string, password: string): Promise<Session & { token: string }> {
  const auth = await request.post(`${GATEWAY}/api/auth/login`, { data: { username, password } });
  expect(auth.ok(), `sign-in as ${username}`).toBe(true);
  const { token, userId } = await auth.json();
  return { token, userId, headers: { Authorization: `Bearer ${token}` } };
}

async function open(page: Page, token: string, path: string, ready: string) {
  await page.goto("/login");
  await setSessionCookie(page, token);
  await page.goto(path);
  await expect(page.getByText(ready).first()).toBeVisible({ timeout: 90_000 });
}

async function get<T>(request: APIRequestContext, session: Session, path: string): Promise<T> {
  const response = await request.get(`${GATEWAY}${path}`, { headers: session.headers });
  expect(response.ok(), path).toBe(true);
  return response.json();
}

async function usdAccount(request: APIRequestContext, session: Session) {
  const accounts = await get<{ id: number; currency: string; status: string; balance: number; accountNumber: string }[]>(
    request, session, `/api/accounts/user/${session.userId}`);
  const account = accounts.find((a) => a.currency === "USD" && a.status === "ACTIVE");
  expect(account, "an active USD account").toBeTruthy();
  return account!;
}

const balanceOf = async (request: APIRequestContext, session: Session, id: number) =>
  Number((await get<{ balance: number }>(request, session, `/api/accounts/${id}`)).balance);

/** A new personal loan for the seeded customer, accepted and waiting to be paid out. */
async function pendingLoan(request: APIRequestContext, session: Session): Promise<number> {
  const applied = await request.post(`${GATEWAY}/api/applications`, {
    headers: session.headers,
    data: {
      userId: session.userId, applicationType: "PERSONAL_LOAN", currency: "USD", requestedAmount: 3000,
      termMonths: 12, purpose: "Servicing check", annualIncome: 90000, monthlyDebtObligations: 300,
    },
  });
  expect(applied.ok()).toBe(true);
  const application = await applied.json();
  expect(application.status).toBe("OFFERED");
  const accepted = await request.post(`${GATEWAY}/api/applications/${application.id}/offer/accept`, {
    headers: session.headers,
  });
  expect(accepted.ok()).toBe(true);
  let productId = 0;
  await expect
    .poll(async () => {
      const app = await get<{ status: string; productId: number | null }>(request, session, `/api/applications/${application.id}`);
      productId = app.productId ?? 0;
      return app.status;
    }, { timeout: 180_000, intervals: [2_000] })
    .toBe("PROVISIONED");
  return productId;
}

test("a borrower receives a loan, pays an instalment, and pays it off", async ({ page, request }) => {
  const session = await login(request, USERNAME, PASSWORD);
  const account = await usdAccount(request, session);
  const loanId = await pendingLoan(request, session);
  const loan = await get<{ status: string; principal: number }>(request, session, `/api/loans/${loanId}`);
  expect(loan.status).toBe("PENDING");

  await open(page, session.token, `/loans/${loanId}`, "Original principal");
  const last4 = account.accountNumber.slice(-4);

  // Receive the money.
  let before = await balanceOf(request, session, account.id);
  const receive = page.getByTestId("receive-funds-form");
  await receive.getByLabel("Receive into").selectOption(String(account.id));
  await receive.getByRole("button", { name: "Review" }).click();
  await expect(page.getByTestId("receive-funds-review")).toContainText(`••••${last4}`);
  await receive.getByRole("button", { name: "Receive funds" }).click();
  await expect(page.getByTestId("receive-funds-receipt")).toContainText("Funds sent to your account.", { timeout: 60_000 });
  expect((await get<{ status: string }>(request, session, `/api/loans/${loanId}`)).status).toBe("ACTIVE");
  expect(await balanceOf(request, session, account.id)).toBeCloseTo(before + Number(loan.principal), 2);

  // Pay the first instalment.
  const repay = page.getByTestId("loan-repay-form");
  await expect(repay).toBeVisible({ timeout: 60_000 });
  const schedule = await get<{ paymentNumber: number; scheduledPayment: number }[]>(
    request, session, `/api/loans/${loanId}/schedule`);
  const first = Number(schedule.find((r) => r.paymentNumber === 1)!.scheduledPayment);
  before = await balanceOf(request, session, account.id);
  await repay.getByRole("radio", { name: /Instalment 1/ }).check();
  await repay.getByLabel("Pay from").selectOption(String(account.id));
  await repay.getByRole("button", { name: "Review" }).click();
  await repay.getByRole("button", { name: "Confirm payment" }).click();
  await expect(page.getByTestId("loan-repay-receipt")).toContainText("Payment made.", { timeout: 60_000 });
  const repayments = await get<{ amount: number; isEarlyPayoff: boolean }[]>(request, session, `/api/loans/${loanId}/repayments`);
  expect(repayments).toHaveLength(1);
  expect(Number(repayments[0].amount)).toBeCloseTo(first, 2);
  expect(await balanceOf(request, session, account.id)).toBeCloseTo(before - first, 2);

  // Pay the rest off.
  await page.reload();
  const payoff = page.getByTestId("loan-payoff-form");
  await expect(payoff).toBeVisible({ timeout: 90_000 });
  const quote = await get<{ totalPayoffAmount: number }>(request, session, `/api/loans/${loanId}/payoff-quote`);
  before = await balanceOf(request, session, account.id);
  await payoff.getByLabel("Pay from").selectOption(String(account.id));
  await payoff.getByRole("button", { name: "Review" }).click();
  await payoff.getByRole("button", { name: "Pay off loan" }).click();
  await expect(page.getByTestId("loan-payoff-receipt")).toContainText("Loan paid off.", { timeout: 60_000 });

  const closed = await get<{ status: string; remainingBalance: number }>(request, session, `/api/loans/${loanId}`);
  expect(closed.status).toBe("PAID_OFF");
  expect(Number(closed.remainingBalance)).toBe(0);
  expect(await balanceOf(request, session, account.id)).toBeCloseTo(before - Number(quote.totalPayoffAmount), 2);
  // The receipt outlived the page changing its mind about what to offer.
  await expect(page.getByTestId("loan-payoff-receipt")).toBeVisible();
  await expect(page.getByText("This loan is paid off. Nothing more is owed.")).toBeVisible();
});

test("a cardholder pays part of a card balance, then the rest", async ({ page, request }) => {
  const session = await login(request, USERNAME, PASSWORD);
  const staff = await login(request, STAFF_USERNAME, STAFF_PASSWORD);
  const account = await usdAccount(request, session);
  const cards = await get<{ id: number; status: string; currency: string }[]>(
    request, session, `/api/credit-cards/user/${session.userId}`);
  const card = cards.find((c) => c.status === "ACTIVE" && c.currency === "USD");
  test.skip(!card, "The seeded customer has no active card.");

  // Spending is simulated by staff: customers cannot mint their own purchases.
  const bought = await request.post(`${GATEWAY}/api/credit-cards/${card!.id}/purchase`, {
    headers: { ...staff.headers, "Idempotency-Key": randomUUID() },
    data: { amount: 60, description: "Servicing check", merchantName: "Example Store", merchantCategory: "RETAIL" },
  });
  expect(bought.ok(), "staff purchase").toBe(true);
  const owed = Number((await get<{ currentBalance: number }>(request, session, `/api/credit-cards/${card!.id}`)).currentBalance);
  expect(owed).toBeGreaterThanOrEqual(60);

  await open(page, session.token, `/cards/${card!.id}`, "Credit limit");
  const pay = page.getByTestId("card-pay-form");

  let before = await balanceOf(request, session, account.id);
  await pay.getByRole("radio", { name: "Other amount" }).check();
  await pay.getByRole("textbox", { name: "Other amount" }).fill("10.00");
  await pay.getByLabel("Pay from").selectOption(String(account.id));
  await pay.getByRole("button", { name: "Review" }).click();
  await pay.getByRole("button", { name: "Confirm payment" }).click();
  await expect(page.getByTestId("card-pay-receipt")).toContainText("$10.00", { timeout: 60_000 });
  expect(Number((await get<{ currentBalance: number }>(request, session, `/api/credit-cards/${card!.id}`)).currentBalance))
    .toBeCloseTo(owed - 10, 2);
  expect(await balanceOf(request, session, account.id)).toBeCloseTo(before - 10, 2);

  await page.getByRole("button", { name: "Make another payment" }).click();
  before = await balanceOf(request, session, account.id);
  const pay2 = page.getByTestId("card-pay-form");
  await pay2.getByRole("radio", { name: /Current balance/ }).check();
  await pay2.getByLabel("Pay from").selectOption(String(account.id));
  await pay2.getByRole("button", { name: "Review" }).click();
  await pay2.getByRole("button", { name: "Confirm payment" }).click();
  await expect(page.getByTestId("card-pay-receipt")).toContainText("Payment made.", { timeout: 60_000 });
  expect(Number((await get<{ currentBalance: number }>(request, session, `/api/credit-cards/${card!.id}`)).currentBalance)).toBe(0);
  expect(await balanceOf(request, session, account.id)).toBeCloseTo(before - (owed - 10), 2);
});

test("the servicing forms fit a phone without sideways scrolling", async ({ page, request }) => {
  const session = await login(request, USERNAME, PASSWORD);
  const loans = await get<{ id: number; status: string }[]>(request, session, `/api/loans/user/${session.userId}`);
  const loan = loans.find((l) => l.status === "ACTIVE") ?? loans[0];
  test.skip(!loan, "The seeded customer has no loan.");
  await page.setViewportSize({ width: 375, height: 800 });
  await open(page, session.token, `/loans/${loan.id}`, "Original principal");
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
