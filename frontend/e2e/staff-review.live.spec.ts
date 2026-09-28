import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * The manual-review workbench against a running stack.
 *
 * A newly registered customer has not finished an identity check, so policy
 * refers their credit application to a person: the real route into the queue.
 *
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     npx playwright test --project=live staff-review
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const STAFF_USERNAME = process.env.NORTHBANK_DEMO_STAFF_USERNAME ?? "northbank.reviewer";
const STAFF_PASSWORD = process.env.NORTHBANK_DEMO_STAFF_PASSWORD ?? "ReviewerDemo2026!";

test.describe.configure({ timeout: 240_000 });

async function staffSession(request: APIRequestContext) {
  const response = await request.post(`${GATEWAY}/api/auth/login`, {
    data: { username: STAFF_USERNAME, password: STAFF_PASSWORD },
  });
  expect(response.ok()).toBe(true);
  const { token, userId } = await response.json();
  return { token: token as string, userId: userId as number, headers: { Authorization: `Bearer ${token}` } };
}

/** A referred personal-loan application from a fresh, unverified customer. */
async function referredApplication(request: APIRequestContext) {
  const stamp = `${Date.now()}`.slice(-9);
  const reg = await request.post(`${GATEWAY}/api/auth/register`, {
    data: {
      username: `review.demo.${stamp}`, email: `review.demo.${stamp}@example.com`, password: "Northbank2026A",
      firstName: "Jordan", lastName: "Lee", dateOfBirth: "1989-02-20", phone: "2405550152",
      streetAddress: "3 Sample Lane", city: "Silver Spring", state: "MD", postalCode: "20910",
      ssn: "123-45-6789",
    },
  });
  expect(reg.ok()).toBe(true);
  const { token, userId } = await reg.json();
  const headers = { Authorization: `Bearer ${token}` };
  const applied = await request.post(`${GATEWAY}/api/applications`, {
    headers,
    data: {
      userId, applicationType: "PERSONAL_LOAN", currency: "USD", requestedAmount: 6000, termMonths: 24,
      purpose: "Car repairs", annualIncome: 80000, monthlyDebtObligations: 400,
    },
  });
  expect(applied.ok()).toBe(true);
  const application = await applied.json();
  expect(application.status).toBe("MANUAL_REVIEW");
  return { id: application.id as number, customerHeaders: headers };
}

async function openWorkbench(page: Page, token: string, id: number) {
  await page.goto("/login");
  await setSessionCookie(page, token);
  await page.goto(`/admin/applications/${id}`);
  await page.getByRole("heading", { level: 1 }).waitFor({ timeout: 90_000 });
}

test("a reviewer sees the evidence and approves a referral into an offer, not a product", async ({ page, request }) => {
  const staff = await staffSession(request);
  const app = await referredApplication(request);

  await openWorkbench(page, staff.token, app.id);
  await expect(page.getByText("KYC_REVIEW_REQUIRED")).toBeVisible();
  await expect(page.getByText("$80,000.00")).toBeVisible();

  await page.getByRole("button", { name: "Approve" }).click();
  await page.getByLabel("Amount to approve").fill("5000.00");
  await page.getByLabel("Reviewer notes").fill("Identity documents seen");
  await page.getByRole("button", { name: "Confirm approval" }).click();
  await expect(page.getByText(/An offer has been made/)).toBeVisible({ timeout: 60_000 });

  const stored = await (await request.get(`${GATEWAY}/api/applications/${app.id}`, { headers: staff.headers })).json();
  expect(stored.status).toBe("OFFERED");
  expect(stored.productId).toBeNull();
  const offers = await (await request.get(`${GATEWAY}/api/applications/${app.id}/offers`, { headers: staff.headers })).json();
  expect(Number(offers[0].approvedAmount)).toBe(5000);

  // The policy's referral and the reviewer's approval are both on record.
  const decisions = await (await request.get(`${GATEWAY}/api/applications/${app.id}/decisions`, { headers: staff.headers })).json();
  expect(decisions.map((d: { decidedBy: string }) => d.decidedBy)).toEqual(["POLICY", "REVIEWER"]);

  // Staff decided to lend; answering the offer is the customer's alone.
  const staffAccept = await request.post(`${GATEWAY}/api/applications/${app.id}/offer/accept`, { headers: staff.headers });
  expect(staffAccept.status()).toBe(403);

  await page.reload();
  await expect(page.getByText("Reviewer #" + staff.userId)).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
});

test("a reviewer's rejection is final and makes no offer", async ({ page, request }) => {
  const staff = await staffSession(request);
  const app = await referredApplication(request);

  await openWorkbench(page, staff.token, app.id);
  await page.getByRole("button", { name: "Reject" }).click();
  await page.getByLabel("Reason for rejection").fill("Could not verify identity");
  await page.getByRole("button", { name: "Confirm rejection" }).click();
  await expect(page.getByText(/No offer will be made/)).toBeVisible({ timeout: 60_000 });

  const stored = await (await request.get(`${GATEWAY}/api/applications/${app.id}`, { headers: staff.headers })).json();
  expect(stored.status).toBe("REJECTED");
  const offers = await (await request.get(`${GATEWAY}/api/applications/${app.id}/offers`, { headers: staff.headers })).json();
  expect(offers).toHaveLength(0);
});

test("a customer cannot decide their own referral", async ({ request }) => {
  const app = await referredApplication(request);
  const refused = await request.put(`${GATEWAY}/api/applications/${app.id}/review`, {
    headers: app.customerHeaders,
    data: { decision: "APPROVE" },
  });
  expect(refused.status()).toBe(403);
});
