import { expect, test, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * Portfolio screenshots of the signed-in product.
 *
 * Captured from the real application against a running stack, signed in as the
 * synthetic customer that `scripts/seed-demo.sh` creates. Every figure in these
 * images is that customer's actual data, read from the services through the
 * gateway — nothing is mocked and nothing is typed in to look good.
 *
 * Reviewed by eye, so kept out of the CI projects and run deliberately:
 *
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     E2E_USERNAME=... E2E_PASSWORD=... \
 *     npx playwright test --project=screenshots banking-screenshots
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const USERNAME = process.env.E2E_USERNAME ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "DemoPassword123!";

const DESKTOP = { width: 1440, height: 900 };
const PHONE = { width: 390, height: 844 };
const OUT = "../docs/screenshots";

test.skip(!USERNAME, "Set E2E_USERNAME and E2E_PASSWORD from scripts/seed-demo.sh output.");

test.describe.configure({ timeout: 180_000 });

async function signIn(page: Page, request: import("@playwright/test").APIRequestContext) {
  const auth = await request.post(`${GATEWAY}/api/auth/login`, {
    data: { username: USERNAME, password: PASSWORD },
  });
  const { token, userId } = await auth.json();
  await page.goto("/login");
  await setSessionCookie(page, token);
  return { token, userId, headers: { Authorization: `Bearer ${token}` } };
}

/** Waits for content, then for the chart and fonts to stop moving. */
async function settle(page: Page) {
  await page
    .locator('[aria-busy="true"]')
    .waitFor({ state: "detached", timeout: 90_000 })
    .catch(() => {});
  await page.getByRole("heading", { level: 1 }).waitFor({ state: "visible", timeout: 90_000 });
  await page
    .waitForFunction(() => document.fonts?.status === "loaded", undefined, { timeout: 5_000 })
    .catch(() => {});
  await page.waitForTimeout(600);
}

/**
 * No screenshot may contain a full Social Security number, a password or a
 * full account number. Asserted rather than assumed: these files get committed.
 */
async function assertNothingSensitive(page: Page) {
  /*
   * `innerText` rather than `textContent`: the latter includes the contents of
   * inline <script> tags, whose timestamps and hashes contain long digit runs
   * that look exactly like a card number to a regular expression. What matters
   * is what appears in the picture, which is the rendered text.
   */
  const visible = await page.locator("body").innerText();
  expect(visible, "a full Social Security number").not.toMatch(/\b\d{3}-\d{2}-\d{4}\b/);
  expect(visible, "the password").not.toContain(PASSWORD);
  expect(visible, "a full account number").not.toMatch(/\bBA\d{12}\b/);
  expect(visible, "a full card number").not.toMatch(/\b\d{13,19}\b/);
}

test.describe("authenticated product screenshots", () => {
  test("dashboard — desktop", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/dashboard");
    await settle(page);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/13-dashboard-desktop.png`, fullPage: true });
  });

  test("dashboard — mobile", async ({ page, request }) => {
    await page.setViewportSize(PHONE);
    await signIn(page, request);
    await page.goto("/dashboard");
    await settle(page);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/14-dashboard-mobile.png`, fullPage: true });
  });

  test("accounts", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/accounts");
    await settle(page);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/15-accounts.png`, fullPage: false });
  });

  test("account detail", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/accounts");
    await settle(page);

    await page.getByRole("link", { name: /view account/i }).first().click();
    await page.waitForURL(/\/accounts\/\d+$/, { timeout: 90_000 });
    await settle(page);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/16-account-detail.png`, fullPage: false });
  });

  test("transactions", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/transactions");
    await settle(page);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/17-transactions.png`, fullPage: false });
  });

  /*
   * Move Money, at each step of the journey. The deposit is a real one: the
   * receipt has to carry a reference the backend actually issued, and an
   * invented one would be the very thing this project refuses to draw.
   */
  test("move money — details, review and receipt", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    const { userId, headers } = await signIn(page, request);
    const accounts = await (
      await request.get(`${GATEWAY}/api/accounts/user/${userId}`, { headers })
    ).json();

    /*
     * The money has to be there. Picking the first account regardless of its
     * balance is how this capture first ran, and the screenshot it produced was
     * a refusal — correct behaviour, and not what this file is for.
     */
    const amount = 250;
    const funded = [...accounts]
      .filter((a: { balance: number }) => a.balance > amount)
      .sort((a: { balance: number }, b: { balance: number }) => b.balance - a.balance);
    test.skip(funded.length === 0 || accounts.length < 2, "needs a funded account and somewhere to send it");

    const source = funded[0];
    const destination = accounts.find((a: { id: number }) => a.id !== source.id);

    await page.goto("/move-money");
    await settle(page);

    await page.getByLabel("From").selectOption(String(source.id));
    await page.getByLabel("To").selectOption(String(destination.id));
    await page.getByLabel("Amount").fill(amount.toFixed(2));
    await page.getByLabel("Description").fill("Monthly saving");
    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/22-move-money-details.png`, fullPage: false });

    await page.getByRole("button", { name: /review transfer/i }).click();
    await page.getByTestId("review-panel").waitFor();
    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/23-move-money-review.png`, fullPage: false });

    await page.getByRole("button", { name: /confirm transfer/i }).click();
    await page.getByTestId("receipt").waitFor({ timeout: 120_000 });
    await settle(page);
    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/24-move-money-receipt.png`, fullPage: false });
  });

  test("payments", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/payments");
    await settle(page);

    // Open the payee form, so the capture shows what the page can do.
    await page.getByRole("button", { name: /add payee/i }).click();
    await page.waitForTimeout(300);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/25-payments.png`, fullPage: false });
  });

  test("credit cards", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/cards");
    await settle(page);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/18-cards.png`, fullPage: false });
  });

  test("loans", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/loans");
    await settle(page);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/19-loans.png`, fullPage: false });
  });

  test("profile and security", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/profile");
    await settle(page);

    // The one page that shows identity detail, and the one most worth checking
    // before the image is committed.
    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/20-profile-security.png`, fullPage: true });
  });

  test("notifications", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, request);
    await page.goto("/notifications");
    await settle(page);

    await assertNothingSensitive(page);
    await page.screenshot({ path: `${OUT}/21-notifications.png`, fullPage: false });
  });
});
