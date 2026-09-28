import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * Staff workflows against a running stack, driven through the console.
 *
 * Each test registers its own synthetic customer through the public API, so it
 * starts from a known state: identity unverified, nothing decided. The staff
 * side is the demo reviewer that docker-compose enables.
 *
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     npx playwright test --project=live staff-workflows
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const STAFF_USERNAME = process.env.NORTHBANK_DEMO_STAFF_USERNAME ?? "northbank.reviewer";
const STAFF_PASSWORD = process.env.NORTHBANK_DEMO_STAFF_PASSWORD ?? "ReviewerDemo2026!";

test.describe.configure({ timeout: 240_000 });

async function login(request: APIRequestContext, username: string, password: string) {
  const response = await request.post(`${GATEWAY}/api/auth/login`, { data: { username, password } });
  expect(response.ok(), `sign-in as ${username}`).toBe(true);
  const { token, userId } = await response.json();
  return { token, userId: userId as number, headers: { Authorization: `Bearer ${token}` } };
}

/** A new synthetic customer with two identity documents submitted. */
async function customerAwaitingKyc(request: APIRequestContext) {
  const stamp = `${Date.now()}`.slice(-9);
  const reg = await request.post(`${GATEWAY}/api/auth/register`, {
    data: {
      username: `kyc.demo.${stamp}`, email: `kyc.demo.${stamp}@example.com`, password: "Northbank2026A",
      firstName: "Alex", lastName: "Morgan", dateOfBirth: "1991-06-12", phone: "2405550151",
      streetAddress: "8 Sample Road", city: "Silver Spring", state: "MD", postalCode: "20910",
      ssn: "123-45-6789",
    },
  });
  expect(reg.ok(), "register").toBe(true);
  const { token, userId } = await reg.json();
  const headers = { Authorization: `Bearer ${token}` };
  for (const [documentType, documentRef] of [["PASSPORT", `DEMO-PASSPORT-${stamp}`], ["PROOF_OF_ADDRESS", `DEMO-ADDRESS-${stamp}`]]) {
    const doc = await request.post(`${GATEWAY}/api/users/${userId}/kyc/documents`, { headers, data: { documentType, documentRef } });
    expect(doc.ok(), `submit ${documentType}`).toBe(true);
  }
  return { userId: userId as number, headers };
}

async function openAs(page: Page, token: string, path: string) {
  await page.goto("/login");
  await setSessionCookie(page, token);
  await page.goto(path);
  await page.getByRole("heading", { level: 1 }).waitFor({ timeout: 90_000 });
}

test("staff review identity documents and verify a customer, and underwriting sees it", async ({ page, request }) => {
  const customer = await customerAwaitingKyc(request);
  const staff = await login(request, STAFF_USERNAME, STAFF_PASSWORD);

  await openAs(page, staff.token, `/admin/kyc?userId=${customer.userId}`);
  await expect(page.getByText("Identity decision")).toBeVisible();

  // Approve the first document through the review panel.
  await page.getByRole("button", { name: "Approve", exact: true }).first().click();
  await expect(page.getByText("Document approved.")).toBeVisible({ timeout: 60_000 });
  const docs = await (await request.get(`${GATEWAY}/api/users/${customer.userId}/kyc/documents`, { headers: staff.headers })).json();
  const approved = docs.find((d: { status: string }) => d.status === "APPROVED");
  expect(approved, "one document approved").toBeTruthy();
  // Recorded under the signed-in reviewer, not a caller-supplied id.
  expect(approved.reviewedBy).toBe(staff.userId);

  // Rejecting the other asks for a reason first; the first click must not
  // submit on its own.
  await page.getByRole("button", { name: "Reject", exact: true }).first().click();
  await page.getByLabel("Reason for rejection").fill("Address document is out of date");
  await page.getByRole("button", { name: "Confirm rejection" }).click();
  await expect(page.getByText("Document rejected.")).toBeVisible({ timeout: 60_000 });
  const after = await (await request.get(`${GATEWAY}/api/users/${customer.userId}/kyc/documents`, { headers: staff.headers })).json();
  const rejected = after.find((d: { status: string }) => d.status === "REJECTED");
  expect(rejected?.rejectionReason).toBe("Address document is out of date");

  // Then the customer-level decision, which is what underwriting reads.
  await page.getByRole("button", { name: "Approve identity" }).click();
  await page.getByRole("button", { name: "Confirm approval" }).click();
  await expect(page.getByText("Identity approved.")).toBeVisible({ timeout: 60_000 });

  const profile = await (await request.get(`${GATEWAY}/api/users/${customer.userId}`, { headers: staff.headers })).json();
  expect(profile.kycStatus).toBe("APPROVED");

  // Underwriting sees the same decision: a verified customer's card
  // application is decided by policy instead of being referred for identity.
  const applied = await request.post(`${GATEWAY}/api/applications`, {
    headers: customer.headers,
    data: { userId: customer.userId, applicationType: "CREDIT_CARD", currency: "USD", annualIncome: 72000, monthlyDebtObligations: 300 },
  });
  expect(applied.ok()).toBe(true);
  expect((await applied.json()).status).toBe("OFFERED");
});

test("a customer cannot reach the staff identity tools, and the backend refuses them", async ({ page, request }) => {
  const customer = await customerAwaitingKyc(request);
  const refused = await request.put(`${GATEWAY}/api/users/${customer.userId}/kyc/status?status=APPROVED`, {
    headers: customer.headers,
  });
  expect(refused.status()).toBe(403);

  const session = await request.post(`${GATEWAY}/api/auth/login`, {
    data: { username: (await (await request.get(`${GATEWAY}/api/users/${customer.userId}`, { headers: customer.headers })).json()).username, password: "Northbank2026A" },
  });
  const { token } = await session.json();
  await page.goto("/login");
  await setSessionCookie(page, token);
  await page.goto("/admin/kyc");
  await expect(page).not.toHaveURL(/\/admin\/kyc/);
});
