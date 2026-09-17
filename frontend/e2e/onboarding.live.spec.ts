import { expect, test } from "@playwright/test";
import {
  completeWizardToReview,
  fillAccountStep,
  fillPersonalStep,
  syntheticApplicant,
} from "./onboarding";

/**
 * Onboarding against a running stack.
 *
 * The offline suite proves the wizard's rules. This proves the wizard reaches
 * the database: an account is opened through the browser, and the details that
 * were typed come back from the profile endpoint afterwards. A field that
 * survives that round trip was not decoration.
 *
 * It also proves the negative that matters most — that the Social Security
 * number does not come back, because it was never stored.
 *
 *   docker compose up -d
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     npx playwright test --project=live onboarding
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";

/** A complete registration body, which individual tests spoil one field of. */
function registrationBody(overrides: Record<string, unknown> = {}) {
  const suffix = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  return {
    username: `api.${suffix}`,
    email: `api.${suffix}@example.com`,
    password: "Northbank2026",
    firstName: "Avery",
    lastName: "Sinclair",
    dateOfBirth: "1990-01-15",
    phone: "2405550148",
    streetAddress: "123 Example Street",
    city: "Silver Spring",
    state: "MD",
    postalCode: "20910",
    ssn: "123-45-6789",
    ...overrides,
  };
}

test.describe("onboarding, end to end", () => {
  test("an account opened through the wizard keeps every detail that was entered", async ({
    page,
  }) => {
    const who = syntheticApplicant();

    await page.goto("/register");
    await completeWizardToReview(page, who);

    // What the review screen shows is what will be sent — except the number,
    // which is already reduced to the four digits that will survive.
    const form = page.locator("form");
    await expect(form).toContainText(who.email);
    await expect(form).toContainText(`${who.firstName} ${who.middleName} ${who.lastName}`);
    await expect(form).toContainText(who.formattedPhone);
    await expect(form).toContainText(who.streetAddress);
    await expect(form).toContainText(who.stateName);
    await expect(form).toContainText(`•••-••-${who.ssnLast4}`);
    await expect(form).not.toContainText(who.ssn);
    await expect(form).not.toContainText(who.password);

    await page.getByRole("button", { name: "Open my account" }).click();

    // Onboarding ends on its own screen, which reads the profile back from the
    // server — so what it confirms is what was stored, not what was typed.
    await page.waitForURL(/\/welcome$/, { timeout: 60_000 });
    await expect(page.getByRole("heading", { name: "Your account is open" })).toBeVisible();
    await expect(page.getByText("Identity information submitted")).toBeVisible();
    await expect(page.getByText(/verification is pending review/i)).toBeVisible();
    await expect(page.getByText(/identity verified/i)).toHaveCount(0);
    await expect(page.getByText(`•••-••-${who.ssnLast4}`)).toBeVisible();

    // The session is real and lives only in an httpOnly cookie.
    const cookie = (await page.context().cookies()).find((c) => c.name === "bp_session");
    expect(cookie?.httpOnly).toBe(true);
    expect(await page.evaluate(() => document.cookie)).not.toContain("bp_session");

    // The profile reads back from the columns the wizard wrote to.
    await page.goto("/profile");
    const details = page.locator("main");
    await expect(details).toContainText(`${who.firstName} ${who.middleName} ${who.lastName}`);
    await expect(details).toContainText("Jan 15, 1990");
    await expect(details).toContainText(who.formattedPhone);
    await expect(details).toContainText(who.streetAddress);
    await expect(details).toContainText(who.addressLine2);
    await expect(details).toContainText(who.city);
    await expect(details).toContainText(who.stateName);
    await expect(details).toContainText(who.postalCode);
    await expect(details).toContainText(`•••-••-${who.ssnLast4}`);
    await expect(details).toContainText("Submitted — verification pending");

    // Nothing anywhere on the profile page carries the full number.
    const body = (await page.locator("body").textContent()) ?? "";
    expect(body).not.toContain(who.ssn);
    expect(body).not.toContain("123-45-6789");
    expect(body).not.toContain(who.password);
  });

  test("an edit from the review screen changes what is submitted", async ({ page }) => {
    const who = syntheticApplicant();

    await page.goto("/register");
    await completeWizardToReview(page, who);

    await page.getByRole("button", { name: /edit address/i }).click();
    await expect(page.getByRole("heading", { name: "Where you live" })).toBeVisible();
    await page.getByLabel("City").fill("Bethesda");
    await page.getByLabel("ZIP code").fill("20814");
    await page.getByRole("button", { name: "Back to review" }).click();

    await expect(page.locator("form")).toContainText("Bethesda");
    await page.getByRole("button", { name: "Open my account" }).click();
    await page.waitForURL(/\/welcome$/, { timeout: 60_000 });

    await page.goto("/profile");
    await expect(page.locator("body")).toContainText("Bethesda");
    await expect(page.locator("body")).toContainText("20814");
  });

  test("a username already taken sends the customer back to the step that owns it", async ({
    page,
  }) => {
    const first = syntheticApplicant();

    await page.goto("/register");
    await completeWizardToReview(page, first);
    await page.getByRole("button", { name: "Open my account" }).click();
    await page.waitForURL(/\/welcome$/, { timeout: 60_000 });

    // A second applicant who picks the same username. Signed in as the first,
    // so the session is cleared before starting over.
    await page.goto("/dashboard");
    await page.getByRole("button", { name: /sign out/i }).click();
    await page.waitForURL(/\/login$/, { timeout: 60_000 });
    const second = syntheticApplicant({ username: first.username });

    await page.goto("/register");
    await completeWizardToReview(page, second);
    await page.getByRole("button", { name: "Open my account" }).click();

    await expect(page.getByRole("alert").first()).toContainText(/already/i, { timeout: 60_000 });
  });

  test("the submit button locks while the account is being opened", async ({ page }) => {
    // A double-submit would be a second registration attempt with the same
    // username, so the control disables itself the moment it is used.
    const who = syntheticApplicant();

    await page.goto("/register");
    await completeWizardToReview(page, who);

    const submit = page.getByRole("button", { name: "Open my account" });
    await submit.click();

    await expect(page.getByRole("button", { name: /opening your account/i })).toBeDisabled();
    await page.waitForURL(/\/welcome$/, { timeout: 60_000 });
  });

  test("the wizard will not advance past a step the backend would reject", async ({ page }) => {
    const who = syntheticApplicant();

    await page.goto("/register");
    await fillAccountStep(page, who);
    await page.getByRole("button", { name: "Continue" }).click();

    const underage = new Date();
    underage.setFullYear(underage.getFullYear() - 16);
    await fillPersonalStep(page, {
      ...who,
      dateOfBirth: underage.toISOString().slice(0, 10),
    });
    await page.getByRole("button", { name: "Continue" }).click();

    await expect(page.getByText("You must be at least 18 to open an account")).toBeVisible();
    await expect(page.getByRole("heading", { name: "About you" })).toBeVisible();
  });
});

test.describe("the registration API, called directly", () => {
  test("accepts a complete application", async ({ request }) => {
    const response = await request.post(`${GATEWAY}/api/auth/register`, {
      data: registrationBody(),
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(201);
    const body = await response.text();
    // A session, and nothing about the applicant's identity.
    expect(body).not.toContain("123456789");
    expect(body).not.toContain("123-45-6789");
    expect(body).not.toContain("Northbank2026");
  });

  test("refuses an application that the browser would also refuse", async ({ request }) => {
    // The browser is not a security boundary. Each of these bypasses it.
    const rejected: [string, Record<string, unknown>][] = [
      ["no date of birth", { dateOfBirth: null }],
      ["under eighteen", { dateOfBirth: "2015-01-15" }],
      ["a date of birth in the future", { dateOfBirth: "2999-01-01" }],
      ["no street address", { streetAddress: "" }],
      ["a state that is not a code", { state: "Maryland" }],
      ["a malformed ZIP", { postalCode: "209" }],
      ["a formatted phone number", { phone: "(240) 555-0148" }],
      ["a short Social Security number", { ssn: "12345678" }],
      ["no Social Security number", { ssn: null }],
      ["a password from the old policy", { password: "abc123" }],
    ];

    for (const [description, overrides] of rejected) {
      const response = await request.post(`${GATEWAY}/api/auth/register`, {
        data: registrationBody(overrides),
        failOnStatusCode: false,
      });

      expect(response.status(), `${description} should be rejected`).toBe(400);

      // And the rejection does not quote back what was rejected.
      const body = await response.text();
      expect(body, `${description} response`).not.toContain("123-45-6789");
      expect(body, `${description} response`).not.toContain("123456789");
      expect(body, `${description} response`).not.toContain("Northbank2026");
    }
  });

  test("the profile it returns carries four digits of the number and no more", async ({
    request,
  }) => {
    const payload = registrationBody();
    const created = await request.post(`${GATEWAY}/api/auth/register`, { data: payload });
    expect(created.status()).toBe(201);

    const { token, userId } = await created.json();
    const profile = await request.get(`${GATEWAY}/api/users/${userId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(profile.status()).toBe(200);
    const body = await profile.json();

    expect(body.ssnLast4).toBe("6789");
    expect(body.identityStatus).toBe("SUBMITTED");
    expect(body.dateOfBirth).toBe("1990-01-15");
    expect(body.state).toBe("MD");
    expect(body.phone).toBe("2405550148");
    expect(body.postalCode).toBe("20910");

    const raw = await profile.text();
    expect(raw).not.toContain("123456789");
    expect(raw).not.toContain("123-45-6789");
    expect(raw).not.toContain("Northbank2026");
    expect(raw).not.toMatch(/"ssn"\s*:/);
    expect(raw).not.toMatch(/"password"\s*:/);
  });

  test("normalises what it stores rather than keeping the display form", async ({ request }) => {
    const payload = registrationBody({ state: "md", postalCode: "20910" });
    const created = await request.post(`${GATEWAY}/api/auth/register`, { data: payload });
    const { token, userId } = await created.json();

    const profile = await request.get(`${GATEWAY}/api/users/${userId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect((await profile.json()).state).toBe("MD");
  });
});
