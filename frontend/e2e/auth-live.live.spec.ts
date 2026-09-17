import { expect, test } from "@playwright/test";

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

// Synthetic, and unique per run so repeated runs do not collide.
function demoIdentity() {
  const suffix = Date.now().toString(36);
  return {
    firstName: "Avery",
    lastName: "Sinclair",
    email: `avery.sinclair.${suffix}@example.com`,
    username: `avery.${suffix}`,
    password: "Northbank2026",
  };
}

test.describe("signed-out journey", () => {
  test("an account can be created, signed out of, and signed back into", async ({ page }) => {
    const demo = demoIdentity();

    await page.goto("/register");
    await page.getByLabel("First name").fill(demo.firstName);
    await page.getByLabel("Last name").fill(demo.lastName);
    await page.getByLabel("Email address").fill(demo.email);
    await page.getByLabel("Username").fill(demo.username);
    await page.getByLabel("Password", { exact: true }).fill(demo.password);
    await page.getByLabel("Confirm password").fill(demo.password);

    await page.getByRole("button", { name: "Create account" }).click();

    // Registration issues a session, so the console opens on the dashboard.
    await page.waitForURL(/\/dashboard$/, { timeout: 60_000 });

    // The session cookie is the only place the token lives.
    const cookie = (await page.context().cookies()).find((c) => c.name === "bp_session");
    expect(cookie?.httpOnly).toBe(true);
    expect(await page.evaluate(() => document.cookie)).not.toContain("bp_session");

    await page.getByRole("button", { name: /sign out/i }).click();
    await page.waitForURL(/\/login$/, { timeout: 60_000 });

    await page.getByLabel("Username").fill(demo.username);
    await page.getByLabel("Password", { exact: true }).fill(demo.password);
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
      },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(400);
  });
});
