import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * Portfolio screenshots of the credit journey.
 *
 * Captured against a running stack as the synthetic customer that
 * `scripts/seed-demo.sh` creates, plus the demo reviewer for the staff queue.
 * The open offer and the referred application are created through the public
 * API in the test, so every term shown is one the services actually decided.
 *
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     E2E_USERNAME=... E2E_PASSWORD=... \
 *     npx playwright test --project=screenshots credit-screenshots
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const USERNAME = process.env.E2E_USERNAME ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "DemoPassword123!";
const STAFF_USERNAME = process.env.NORTHBANK_DEMO_STAFF_USERNAME ?? "northbank.reviewer";
const STAFF_PASSWORD = process.env.NORTHBANK_DEMO_STAFF_PASSWORD ?? "ReviewerDemo2026!";
const DESKTOP = { width: 1440, height: 900 };
const OUT = "../docs/screenshots";

test.skip(!USERNAME, "Set E2E_USERNAME and E2E_PASSWORD from scripts/seed-demo.sh output.");
test.describe.configure({ timeout: 180_000, mode: "serial" });

async function login(request: APIRequestContext, username: string, password: string) {
  const auth = await request.post(`${GATEWAY}/api/auth/login`, { data: { username, password } });
  expect(auth.ok()).toBe(true);
  const { token, userId } = await auth.json();
  return { token, userId, headers: { Authorization: `Bearer ${token}` } };
}

async function openAs(page: Page, token: string, path: string) {
  await page.setViewportSize(DESKTOP);
  await page.goto("/login");
  await setSessionCookie(page, token);
  await page.goto(path);
  await page.getByRole("heading", { level: 1 }).waitFor({ state: "visible", timeout: 90_000 });
  await page
    .waitForFunction(() => document.fonts?.status === "loaded", undefined, { timeout: 5_000 })
    .catch(() => {});
  await page.waitForTimeout(600);
}

/** Nothing sensitive may be in a committed picture. */
async function assertNothingSensitive(page: Page) {
  const visible = await page.locator("body").innerText();
  expect(visible, "a full Social Security number").not.toMatch(/\b\d{3}-\d{2}-\d{4}\b/);
  expect(visible, "the password").not.toContain(PASSWORD);
  expect(visible, "a full account number").not.toMatch(/\bBA\d{12}\b/);
  expect(visible, "a full card number").not.toMatch(/\b\d{13,19}\b/);
}

test("explore credit", async ({ page, request }) => {
  const { token } = await login(request, USERNAME, PASSWORD);
  await openAs(page, token, "/credit");
  await assertNothingSensitive(page);
  await page.screenshot({ path: `${OUT}/26-explore-credit.png`, fullPage: false });
});

test("a personal loan application", async ({ page, request }) => {
  const { token } = await login(request, USERNAME, PASSWORD);
  await openAs(page, token, "/credit/personal-loan");
  await page.getByLabel("How much would you like to borrow?").fill("6000.00");
  await page.getByLabel("Over how long?").selectOption("36");
  await page.getByLabel("What is it for?").fill("Kitchen renovation");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByLabel("Your annual income before tax").fill("90000.00");
  await page.getByLabel("What you already pay each month towards other debts").fill("450.00");
  await page.getByRole("button", { name: "Continue" }).click();
  // The review step: everything stated, before anything is sent.
  await page.getByTestId("application-review").waitFor();
  await assertNothingSensitive(page);
  await page.screenshot({ path: `${OUT}/27-credit-application.png`, fullPage: false });
});

test("my applications with an open offer", async ({ page, request }) => {
  const { token, userId, headers } = await login(request, USERNAME, PASSWORD);
  const applied = await request.post(`${GATEWAY}/api/applications`, {
    headers,
    data: {
      userId, applicationType: "PERSONAL_LOAN", currency: "USD", requestedAmount: 6000,
      termMonths: 36, purpose: "Kitchen renovation", annualIncome: 90000, monthlyDebtObligations: 450,
    },
  });
  expect(applied.ok()).toBe(true);
  expect((await applied.json()).status).toBe("OFFERED");

  await openAs(page, token, "/applications");
  await expect(page.getByRole("heading", { name: "Our offer" }).first()).toBeVisible();
  await assertNothingSensitive(page);
  await page.screenshot({ path: `${OUT}/28-applications-offer.png`, fullPage: false });
});

test("card detail with the self-service freeze", async ({ page, request }) => {
  const { token, userId, headers } = await login(request, USERNAME, PASSWORD);
  const cards: { id: number; status: string }[] = await (
    await request.get(`${GATEWAY}/api/credit-cards/user/${userId}`, { headers })
  ).json();
  const card = cards.find((c) => c.status === "ACTIVE");
  test.skip(!card, "The seeded customer has no active card.");
  await openAs(page, token, `/cards/${card!.id}`);
  await page.getByRole("button", { name: "Freeze card" }).scrollIntoViewIfNeeded();
  await assertNothingSensitive(page);
  await page.screenshot({ path: `${OUT}/29-card-freeze.png`, fullPage: false });
});

test("staff application queue", async ({ page, request }) => {
  // A newly registered customer has not finished an identity check, so their
  // credit application is referred to a person: the real route into the queue.
  const stamp = Date.now().toString().slice(-8);
  const reg = await request.post(`${GATEWAY}/api/auth/register`, {
    data: {
      username: `queue.demo.${stamp}`, email: `queue.demo.${stamp}@example.com`, password: PASSWORD,
      firstName: "Grace", lastName: "Hopper", dateOfBirth: "1988-04-02", phone: "2405550150",
      streetAddress: "12 Sample Avenue", city: "Silver Spring", state: "MD", postalCode: "20910",
      ssn: "123-45-6789",
    },
  });
  expect(reg.ok()).toBe(true);
  const customer = await reg.json();
  const referred = await request.post(`${GATEWAY}/api/applications`, {
    headers: { Authorization: `Bearer ${customer.token}` },
    data: {
      userId: customer.userId, applicationType: "CREDIT_CARD", currency: "USD",
      annualIncome: 72000, monthlyDebtObligations: 300,
    },
  });
  expect((await referred.json()).status).toBe("MANUAL_REVIEW");

  const staff = await login(request, STAFF_USERNAME, STAFF_PASSWORD);
  await openAs(page, staff.token, "/admin/applications?status=MANUAL_REVIEW");
  await assertNothingSensitive(page);
  await page.screenshot({ path: `${OUT}/30-staff-application-queue.png`, fullPage: false });
});
