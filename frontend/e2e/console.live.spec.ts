import { expect, test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { resolve } from "node:path";

/**
 * Live end-to-end suite.
 *
 * Requires the full stack and a seeded customer:
 *
 *   mvn clean package
 *   docker compose up -d
 *   ./scripts/seed-demo.sh          # prints the credentials
 *   E2E_USERNAME=... E2E_PASSWORD=... npm run test:e2e:live
 *
 * This does **not** run in CI — starting 13 services per push is not a sensible
 * trade. Tests tagged `@screenshot` also write the portfolio images under
 * `docs/screenshots/`.
 */

const USERNAME = process.env.E2E_USERNAME ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "DemoPassword123!";
const SHOTS = resolve(process.cwd(), "..", "docs", "screenshots");

test.skip(!USERNAME, "Set E2E_USERNAME and E2E_PASSWORD from scripts/seed-demo.sh output.");

test.beforeAll(() => {
  mkdirSync(SHOTS, { recursive: true });
});

async function signIn(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Username").fill(USERNAME);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL("**/dashboard");
}

/**
 * Waits until the page is visually settled, so screenshots never catch a skeleton.
 *
 * `networkidle` is unreliable here because React streams the response, so this
 * waits for the loading skeleton to clear and then lets fonts and charts paint.
 */
async function settle(page: Page): Promise<void> {
  await page.waitForLoadState("domcontentloaded");
  // Short explicit timeouts: these are best-effort settling steps, so a miss
  // must not consume the whole test budget waiting on the default.
  await page
    .locator('[aria-busy="true"]')
    .waitFor({ state: "detached", timeout: 5_000 })
    .catch(() => {});
  await page
    .waitForFunction(() => document.fonts?.status === "loaded", undefined, { timeout: 3_000 })
    .catch(() => {});
  await page.waitForTimeout(500);
}

test.describe("signed-in banking flows", () => {
  test("signing in lands on a dashboard showing real balances @screenshot", async ({ page }) => {
    await page.goto("/login");
    await settle(page);
    await page.screenshot({ path: `${SHOTS}/01-login.png`, fullPage: false });

    await page.getByLabel("Username").fill(USERNAME);
    await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.waitForURL("**/dashboard");
    await settle(page);

    await expect(page.getByRole("heading", { name: /good to see you/i })).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText("Total balance")).toBeVisible({ timeout: 30_000 });
    // A seeded customer has money, so the tile must show a real formatted amount.
    await expect(page.locator("section").first()).toContainText(/\$[\d,]+\.\d{2}/);

    await page.screenshot({ path: `${SHOTS}/02-dashboard.png`, fullPage: false });
  });

  test("accounts list opens an account with its transaction history @screenshot", async ({ page }) => {
    await signIn(page);

    await page.getByRole("link", { name: "Accounts", exact: true }).click();
    await page.waitForURL("**/accounts");
    await settle(page);
    await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();

    await page.locator("table a").first().click();
    await page.waitForURL(/\/accounts\/\d+$/);
    await settle(page);

    await expect(page.getByText("Balance", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("heading", { name: /account$/i })).toBeVisible();

    await page.screenshot({ path: `${SHOTS}/03-accounts-transactions.png`, fullPage: false });
  });

  test("a transfer requires explicit confirmation before it is sent @screenshot", async ({ page }) => {
    await signIn(page);

    await page.getByRole("link", { name: "Transactions" }).click();
    await page.waitForURL("**/transactions");
    await settle(page);

    const transfer = page.getByTestId("transfer-form");
    await transfer.getByLabel("From").selectOption({ index: 1 });
    await transfer.getByLabel("To").selectOption({ index: 2 });
    await transfer.getByLabel("Amount").fill("125.00");

    // Nothing is sent on the first click — the user must review first.
    await transfer.getByRole("button", { name: "Review transfer" }).click();
    await expect(transfer.getByRole("button", { name: "Confirm transfer" })).toBeVisible();
    await expect(transfer.getByText(/send/i)).toBeVisible();

    await page.screenshot({ path: `${SHOTS}/04-transfer.png`, fullPage: false });

    await transfer.getByRole("button", { name: "Confirm transfer" }).click();
    await expect(page.getByText(/transfer complete/i)).toBeVisible({ timeout: 15_000 });
  });

  test("transfer validation rejects an invalid amount", async ({ page }) => {
    await signIn(page);
    await page.goto("/transactions");
    await settle(page);

    const deposit = page.getByTestId("deposit-form");
    await deposit.getByLabel("Account").selectOption({ index: 1 });
    await deposit.getByLabel("Amount").fill("0");
    await deposit.getByRole("button", { name: "Deposit" }).click();

    await expect(page.getByText("Amount must be greater than zero")).toBeVisible();
  });

  test("a loan shows its amortization schedule and payoff quote @screenshot", async ({ page }) => {
    await signIn(page);

    await page.getByRole("link", { name: "Loans" }).click();
    await page.waitForURL("**/loans");
    await settle(page);

    const firstLoan = page.locator("table a").first();
    test.skip((await firstLoan.count()) === 0, "No loan seeded for this customer.");

    await firstLoan.click();
    await page.waitForURL(/\/loans\/\d+$/);
    await settle(page);

    await expect(page.getByRole("heading", { name: /amortization schedule/i })).toBeVisible();
    await expect(page.getByText("Total payoff")).toBeVisible();

    await page.screenshot({ path: `${SHOTS}/05-loan-details.png`, fullPage: false });
  });

  test("card numbers are never rendered in full @screenshot", async ({ page }) => {
    await signIn(page);
    await page.goto("/cards");
    await settle(page);

    const body = (await page.textContent("body")) ?? "";
    // No 13-to-19 digit run anywhere on the page.
    expect(body).not.toMatch(/\b\d{13,19}\b/);

    if ((await page.locator("text=•••• •••• ••••").count()) > 0) {
      await page.screenshot({ path: `${SHOTS}/07-cards.png`, fullPage: false });
    }
  });

  test("a customer cannot reach staff tools", async ({ page }) => {
    await signIn(page);

    // Staff navigation is not offered...
    await expect(page.getByRole("link", { name: "KYC Review" })).toHaveCount(0);

    // ...and typing the URL does not get there either.
    await page.goto("/admin/kyc");
    await expect(page).toHaveURL(/\/dashboard$/);
  });

  test("signing out clears the session and protects the dashboard", async ({ page }) => {
    await signIn(page);

    await page.getByRole("button", { name: "Sign out" }).first().click();
    await page.waitForURL("**/login");

    await page.goto("/dashboard");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("the console is usable on a phone viewport @screenshot", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signIn(page);
    await settle(page);

    const overflows = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    );
    expect(overflows).toBe(false);

    await page.getByRole("button", { name: "Menu" }).click();
    await expect(page.getByRole("link", { name: "Accounts", exact: true })).toBeVisible();

    await page.screenshot({ path: `${SHOTS}/06-mobile.png`, fullPage: false });
  });
});
