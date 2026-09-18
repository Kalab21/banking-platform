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
  const { token } = await auth.json();
  await page.goto("/login");
  await setSessionCookie(page, token);
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
  const body = (await page.locator("body").textContent()) ?? "";
  expect(body, "a full Social Security number").not.toMatch(/\b\d{3}-\d{2}-\d{4}\b/);
  expect(body, "the password").not.toContain(PASSWORD);
  expect(body, "a full account number").not.toMatch(/\bBA\d{12}\b/);
  expect(body, "a full card number").not.toMatch(/\b\d{13,19}\b/);
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
