import { expect, test } from "@playwright/test";
import { buildToken, forbidDirectGatewayCalls, setSessionCookie } from "./fixtures";

/**
 * Offline end-to-end suite.
 *
 * Runs against a real production build of the console with **no backend behind
 * it** — the server is pointed at a dead port. That makes this suite cheap
 * enough for every CI push while still exercising the parts that matter without
 * banking data: route protection, cookie handling, form validation, the
 * architecture guarantee that the browser never calls the gateway, and the
 * failure path when the gateway is unreachable.
 *
 * Flows that need real accounts and balances live in `console.live.spec.ts`.
 */

test.beforeEach(async ({ page }) => {
  await forbidDirectGatewayCalls(page);
});

test.describe("route protection", () => {
  test("an anonymous visitor is sent to sign in", async ({ page }) => {
    await page.goto("/dashboard");
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  });

  test("the root path routes an anonymous visitor to sign in", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("an expired session is rejected and returns the user to sign in", async ({ page }) => {
    await page.goto("/login");
    await setSessionCookie(page, buildToken({ exp: 1_600_000_000 }));

    await page.goto("/accounts");

    await expect(page).toHaveURL(/\/login$/);
  });

  test("a malformed session cookie is treated as no session", async ({ page }) => {
    await page.goto("/login");
    await setSessionCookie(page, "not-a-jwt");

    await page.goto("/dashboard");

    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe("sign-in form", () => {
  test("submitting empty credentials reports which field is missing", async ({ page }) => {
    await page.goto("/login");

    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByText("Enter your username")).toBeVisible();
    await expect(page.getByText("Enter your password")).toBeVisible();
    // Nothing was submitted, so the user stays put.
    await expect(page).toHaveURL(/\/login$/);
  });

  test("the password field is masked and the form is keyboard reachable", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByLabel("Password")).toHaveAttribute("type", "password");

    await page.getByLabel("Username").focus();
    await page.keyboard.press("Tab");
    await expect(page.getByLabel("Password")).toBeFocused();
  });

  test("an unreachable gateway is reported as such, not as bad credentials", async ({ page }) => {
    await page.goto("/login");

    await page.getByLabel("Username").fill("demo.customer");
    await page.getByLabel("Password").fill("DemoPassword123!");
    await page.getByRole("button", { name: "Sign in" }).click();

    // The server points at a dead port in this project, so this is the
    // gateway-unreachable path rather than a rejected password.
    // Scoped to the form: Next.js renders its own route announcer with role="alert".
    await expect(
      page.locator("form").getByRole("alert"),
    ).toContainText(/could not reach the banking api/i);
  });
});

test.describe("registration form", () => {
  test("rejects a short password and an invalid email before any request", async ({ page }) => {
    await page.goto("/register");

    await page.getByLabel("First name").fill("Ada");
    await page.getByLabel("Last name").fill("Lovelace");
    await page.getByLabel("Username").fill("ada");
    await page.getByLabel("Email").fill("not-an-email");
    await page.getByLabel("Password").fill("short");

    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Enter a valid email address")).toBeVisible();
    await expect(page.getByText("Password must be at least 8 characters")).toBeVisible();
  });
});

test.describe("architecture", () => {
  test("the session token never appears in the page delivered to the browser", async ({ page }) => {
    const token = buildToken();
    await page.goto("/login");
    await setSessionCookie(page, token);

    const response = await page.goto("/dashboard");
    const body = (await response?.text()) ?? "";

    expect(body).not.toContain(token);
    expect(body).not.toContain(token.split(".")[1]);
  });

  test("the session cookie is httpOnly, so page scripts cannot read it", async ({ page }) => {
    await page.goto("/login");
    await setSessionCookie(page, buildToken());

    const cookie = (await page.context().cookies()).find((c) => c.name === "bp_session");
    expect(cookie?.httpOnly).toBe(true);

    const visibleToScripts = await page.evaluate(() => document.cookie);
    expect(visibleToScripts).not.toContain("bp_session");
  });
});

test.describe("responsive layout", () => {
  test("the sign-in page has no horizontal overflow on a phone", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/login");

    const overflows = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    );
    expect(overflows).toBe(false);
  });

  test("the desktop sign-in page shows the product context column", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/login");

    await expect(
      page.getByRole("heading", { name: /a distributed banking core/i }),
    ).toBeVisible();
  });

  test("the product context column is hidden on a phone", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/login");

    await expect(
      page.getByRole("heading", { name: /a distributed banking core/i }),
    ).toBeHidden();
  });
});
