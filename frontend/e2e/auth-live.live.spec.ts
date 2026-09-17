import { expect, test } from "@playwright/test";
import { completeWizardToReview, syntheticApplicant } from "./onboarding";

/**
 * The signed-out journey against a running stack.
 *
 * Proves the redesigned screens drive the real gateway: an account is created,
 * a session is issued, sign-out clears it, and the same credentials sign back
 * in. Also checks the password policy is the backend's rule and not only the
 * browser's.
 *
 * Requires postgres, eureka, the gateway, user-service and the console:
 *
 *   docker compose up -d postgres redis kafka eureka-server api-gateway \
 *     user-service frontend
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     npx playwright test --project=live auth-live
 */

test.describe("signed-out journey", () => {
  test("an account can be opened, signed out of, and signed back into", async ({ page }) => {
    const who = syntheticApplicant();

    await page.goto("/register");
    await completeWizardToReview(page, who);
    await page.getByRole("button", { name: "Open my account" }).click();

    // Registration issues a session, and onboarding ends on its own screen
    // rather than dropping the customer onto a dashboard they did not ask for.
    await page.waitForURL(/\/welcome$/, { timeout: 60_000 });
    await expect(page.getByRole("heading", { name: "Your account is open" })).toBeVisible();

    // The session cookie is the only place the token lives.
    const cookie = (await page.context().cookies()).find((c) => c.name === "bp_session");
    expect(cookie?.httpOnly).toBe(true);
    expect(await page.evaluate(() => document.cookie)).not.toContain("bp_session");

    await page.getByRole("link", { name: /go to your dashboard/i }).click();
    await page.waitForURL(/\/dashboard$/, { timeout: 60_000 });

    await page.getByRole("button", { name: /sign out/i }).click();
    await page.waitForURL(/\/login$/, { timeout: 60_000 });

    await page.getByLabel("Username").fill(who.username);
    await page.getByLabel("Password", { exact: true }).fill(who.password);
    await page.getByRole("button", { name: "Sign in" }).click();

    await page.waitForURL(/\/dashboard$/, { timeout: 60_000 });
  });

  test("a wrong password is refused without saying which half was wrong", async ({ page }) => {
    await page.goto("/login");

    await page.getByLabel("Username").fill("no.such.person");
    await page.getByLabel("Password", { exact: true }).fill("Northbank2026");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByText("Incorrect username or password.")).toBeVisible({
      timeout: 60_000,
    });

    // The username is kept so only the password is retyped.
    await expect(page.getByLabel("Username")).toHaveValue("no.such.person");
  });

  test("the backend enforces the password policy the form displays", async ({ request }) => {
    const suffix = Date.now().toString(36);

    // Satisfies the old six-character minimum and fails the current rules.
    const response = await request.post("http://localhost:8080/api/auth/register", {
      data: {
        username: `weak.${suffix}`,
        email: `weak.${suffix}@example.com`,
        password: "abc123",
        firstName: "Avery",
        lastName: "Sinclair",
        // Complete otherwise, so the password is the only thing wrong with it.
        dateOfBirth: "1990-01-15",
        phone: "2405550148",
        streetAddress: "123 Example Street",
        city: "Silver Spring",
        state: "MD",
        postalCode: "20910",
        ssn: "123-45-6789",
      },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(400);
  });
});
