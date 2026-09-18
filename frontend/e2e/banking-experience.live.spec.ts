import { expect, test, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * The signed-in product, against a running stack.
 *
 * The offline suite proves the shell and the failure paths. This proves the
 * pages show the customer's actual money: every figure asserted here is read
 * back from the gateway in the same test and compared with what the page
 * rendered, so a number that looks plausible but came from somewhere else
 * fails.
 *
 *   docker compose up -d
 *   ./scripts/seed-demo.sh                      # prints the credentials
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     E2E_USERNAME=... E2E_PASSWORD=... \
 *     npx playwright test --project=live banking-experience
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const USERNAME = process.env.E2E_USERNAME ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "DemoPassword123!";

test.skip(!USERNAME, "Set E2E_USERNAME and E2E_PASSWORD from scripts/seed-demo.sh output.");

/** Money as the pages format it, so API figures and rendered text compare directly. */
function money(amount: number, currency = "USD"): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

/**
 * Puts the browser in a signed-in state and returns the token for API reads.
 *
 * The session is obtained once through the real login endpoint and the cookie
 * planted directly, rather than driving the form in all eleven tests. Every
 * assertion below still goes browser → console → gateway → services; what is
 * skipped is ten repetitions of a login that one test covers properly.
 */
async function signIn(page: Page, request: import("@playwright/test").APIRequestContext) {
  const auth = await request.post(`${GATEWAY}/api/auth/login`, {
    data: { username: USERNAME, password: PASSWORD },
  });
  const { token, userId } = await auth.json();

  await page.goto("/login");
  await setSessionCookie(page, token);
  return { token, userId, headers: { Authorization: `Bearer ${token}` } };
}

/** Signs in the way a customer does, for the test that is about signing in. */
async function signInThroughTheForm(page: Page) {
  await page.goto("/login");
  await page.getByLabel("Username").fill(USERNAME);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL("**/dashboard", { timeout: 90_000 });
}

/**
 * Waits out the loading skeleton, so an assertion is made against content.
 *
 * Generous, because a real thirteen-service stack answers a dashboard's worth
 * of requests considerably more slowly than a stub, and the alternative is a
 * test that fails on machine load rather than on behaviour.
 */
async function settle(page: Page) {
  await page
    .locator('[aria-busy="true"]')
    .waitFor({ state: "detached", timeout: 60_000 })
    .catch(() => {});
  await page.getByRole("heading", { level: 1 }).waitFor({ state: "visible", timeout: 60_000 });
}

/*
 * A real thirteen-service stack on one machine answers a dashboard's worth of
 * requests in tens of seconds, not hundreds of milliseconds. The default budget
 * would measure how busy the host is rather than whether the product works.
 */
test.describe.configure({ timeout: 180_000 });

test.describe("the signed-in product, against real data", () => {
  test("the dashboard totals the customer's actual balances", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);
    await page.goto("/dashboard");
    await settle(page);

    const accounts = await (await request.get(`${GATEWAY}/api/accounts/user/${userId}`, { headers })).json();
    const total = accounts.reduce((sum: number, a: { balance: number }) => sum + a.balance, 0);
    const currency = accounts[0]?.currency ?? "USD";

    // The headline figure is the sum of the accounts the API returns, not a
    // number that merely looks like a balance.
    await expect(page.getByText("Total balance")).toBeVisible({ timeout: 30_000 });
    await expect(page.locator("main")).toContainText(money(total, currency));
    await expect(page.locator("main")).toContainText(
      `Across ${accounts.length} ${accounts.length === 1 ? "account" : "accounts"}`,
    );

    // And every account card carries that account's own balance.
    for (const account of accounts.slice(0, 3)) {
      await expect(page.locator("main")).toContainText(money(account.balance, account.currency));
      await expect(page.locator("main")).toContainText(`••••${account.accountNumber.slice(-4)}`);
    }
  });

  test("the dashboard names the customer and leads with money, not compliance", async ({
    page,
    request,
  }) => {
    const { userId, headers } = await signIn(page, request);
    await page.goto("/dashboard");
    await settle(page);

    const profile = await (await request.get(`${GATEWAY}/api/users/${userId}`, { headers })).json();

    await expect(
      page.getByRole("heading", { level: 1, name: new RegExp(`welcome back, ${profile.firstName}`, "i") }),
    ).toBeVisible({ timeout: 30_000 });

    // The security panel exists, and says what is true rather than claiming a
    // verification nothing performed.
    await expect(page.getByText("Security & identity")).toBeVisible();
    await expect(page.locator("main")).not.toContainText(/identity verified/i);

    // Personal details belong on the profile, not the overview.
    const body = (await page.locator("main").textContent()) ?? "";
    expect(body).not.toContain(profile.ssnLast4 ? `•••-••-${profile.ssnLast4}` : "•••-••-");
    if (profile.streetAddress) expect(body).not.toContain(profile.streetAddress);
    if (profile.dateOfBirth) expect(body).not.toContain(profile.dateOfBirth);
  });

  test("accounts and account detail agree with the API", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);

    const accounts = await (await request.get(`${GATEWAY}/api/accounts/user/${userId}`, { headers })).json();
    const account = accounts[0];

    await page.goto("/accounts");
    await settle(page);
    await expect(page.getByRole("heading", { level: 1, name: "Accounts" })).toBeVisible();
    await expect(page.locator("main")).toContainText(money(account.balance, account.currency));

    // The full number is never rendered, on either page.
    let body = (await page.locator("main").textContent()) ?? "";
    expect(body).not.toContain(account.accountNumber);

    await page.getByRole("link", { name: /view account/i }).first().click();
    await page.waitForURL(/\/accounts\/\d+$/, { timeout: 60_000 });
    await settle(page);

    await expect(page.locator("main")).toContainText(`••••${account.accountNumber.slice(-4)}`);
    body = (await page.locator("main").textContent()) ?? "";
    expect(body).not.toContain(account.accountNumber);
  });

  test("transaction activity matches the recorded transactions", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);

    const accounts = await (await request.get(`${GATEWAY}/api/accounts/user/${userId}`, { headers })).json();
    const response = await request.get(
      `${GATEWAY}/api/transactions/account/${accounts[0].id}?page=0&size=5`,
      { headers },
    );
    // A non-200 here means the transaction service is not answering, which is
    // a different failure from the page rendering the wrong thing. Say which.
    expect(response.status(), "the transaction service did not answer").toBe(200);

    const first = (await response.json())?.content?.[0];
    test.skip(!first, "The seeded customer has no transactions on their first account.");

    await page.goto("/transactions");
    await settle(page);

    await expect(page.getByRole("heading", { level: 1, name: "Transactions" })).toBeVisible();
    await expect(page.locator("main")).toContainText(money(Math.abs(first.amount), first.currency));
    if (first.description) {
      await expect(page.locator("main")).toContainText(first.description);
    }
  });

  test("cards show the real limit, balance and utilisation, and invent nothing", async ({
    page,
    request,
  }) => {
    const { userId, headers } = await signIn(page, request);

    const cards = await (
      await request.get(`${GATEWAY}/api/credit-cards/user/${userId}`, { headers })
    ).json();
    test.skip(cards.length === 0, "The seeded customer has no credit card.");
    const card = cards[0];

    await page.goto("/cards");
    await settle(page);

    await expect(page.locator("main")).toContainText(card.maskedCardNumber);
    await expect(page.locator("main")).toContainText(money(card.currentBalance, card.currency));
    await expect(page.locator("main")).toContainText(money(card.availableCredit, card.currency));

    const expected = Math.max(Math.round((card.currentBalance / card.creditLimit) * 100), 0);
    await expect(page.getByRole("progressbar").first()).toHaveAttribute(
      "aria-valuenow",
      String(Math.min(expected, 100)),
    );

    // Nothing the API does not carry.
    const body = (await page.locator("main").textContent()) ?? "";
    expect(body).not.toMatch(/valid thru|cvv|cvc/i);
    expect(body).not.toMatch(/visa|mastercard/i);
  });

  test("loans show the real balance and a progress figure that matches it", async ({
    page,
    request,
  }) => {
    const { userId, headers } = await signIn(page, request);

    const loans = await (await request.get(`${GATEWAY}/api/loans/user/${userId}`, { headers })).json();
    test.skip(loans.length === 0, "The seeded customer has no loan.");
    const loan = loans[0];

    await page.goto("/loans");
    await settle(page);

    await expect(page.locator("main")).toContainText(money(loan.remainingBalance, loan.currency));
    await expect(page.locator("main")).toContainText(money(loan.monthlyPayment, loan.currency));

    const expected = Math.min(
      Math.max(Math.round(((loan.principal - loan.remainingBalance) / loan.principal) * 100), 0),
      100,
    );
    await expect(page.locator("main")).toContainText(`${expected}% of the original`);
    // The wording credits the balance coming down, not the principal, because
    // the record does not say how much of each payment reached the principal.
    expect((await page.locator("main").textContent()) ?? "").not.toMatch(/principal repaid/i);
  });

  test("the profile holds the personal detail, masked where it should be", async ({
    page,
    request,
  }) => {
    const { userId, headers } = await signIn(page, request);

    const profile = await (await request.get(`${GATEWAY}/api/users/${userId}`, { headers })).json();

    await page.goto("/profile");
    await settle(page);

    await expect(page.getByRole("heading", { level: 1, name: "Profile & security" })).toBeVisible();
    await expect(page.locator("main")).toContainText(profile.email);

    if (profile.ssnLast4) {
      await expect(page.locator("main")).toContainText(`•••-••-${profile.ssnLast4}`);
      await expect(page.locator("main")).toContainText("Submitted — verification pending");
    }

    // Four digits and no more, and never a claim of verification.
    const body = (await page.locator("main").textContent()) ?? "";
    expect(body).not.toMatch(/\b\d{3}-\d{2}-\d{4}\b/);
    expect(body).not.toMatch(/identity verified/i);
    expect(body).not.toContain(PASSWORD);
  });

  test("notifications list what the service recorded", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);

    const data = await (
      await request.get(`${GATEWAY}/api/notifications/user/${userId}?page=0&size=50`, { headers })
    ).json();

    await page.goto("/notifications");
    await settle(page);

    await expect(page.getByRole("heading", { level: 1, name: "Notifications" })).toBeVisible();
    if (data.notifications.length > 0) {
      await expect(page.locator("main")).toContainText(data.notifications[0].title);
    }
  });

  test("a customer cannot reach staff tools, and is not shown them", async ({ page, request }) => {
    await signIn(page, request);
    await page.goto("/dashboard");
    await settle(page);

    await expect(
      page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Fraud Alerts" }),
    ).toHaveCount(0);

    // The backend is what enforces this; the redirect only confirms the UI
    // agrees with it.
    await page.goto("/admin/fraud");
    await expect(page).toHaveURL(/\/dashboard$/, { timeout: 60_000 });
  });

  test("the session survives navigation and ends at sign-out", async ({ page }) => {
    // Six page loads against a real stack. Marked slow rather than trimmed:
    // walking the whole product is the point of the test, and the default
    // budget measures machine load rather than behaviour.
    test.slow();
    await signInThroughTheForm(page);

    for (const path of ["/accounts", "/transactions", "/cards", "/loans", "/profile"]) {
      await page.goto(path);
      await expect(page).toHaveURL(new RegExp(`${path}$`));
    }

    const cookie = (await page.context().cookies()).find((c) => c.name === "bp_session");
    expect(cookie?.httpOnly).toBe(true);
    expect(await page.evaluate(() => document.cookie)).not.toContain("bp_session");

    await page.getByRole("button", { name: /sign out/i }).click();
    await page.waitForURL(/\/login$/, { timeout: 60_000 });

    await page.goto("/dashboard");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("the phone layout works through the real product", async ({ page, request }) => {
    test.slow();
    await page.setViewportSize({ width: 390, height: 844 });
    await signIn(page, request);

    for (const path of ["/dashboard", "/accounts", "/transactions", "/cards", "/loans", "/profile"]) {
      await page.goto(path);
      await settle(page);

      const overflows = await page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
      );
      expect(overflows, `${path} overflows horizontally at 390px`).toBe(false);
    }

    // The drawer still works with real content behind it.
    await page.getByRole("button", { name: "Open menu" }).click();
    await expect(page.getByRole("link", { name: "Accounts" })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("link", { name: "Accounts" })).toBeHidden();
  });
});
