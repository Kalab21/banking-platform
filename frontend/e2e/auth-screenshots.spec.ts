import { expect, test } from "@playwright/test";

/**
 * Portfolio screenshots of the signed-out experience.
 *
 * Run on demand rather than in CI — they write into docs/screenshots and are
 * reviewed by eye, which is not something a pipeline can assert. Everything
 * typed here is obviously synthetic: no real name, email, phone or password.
 *
 *   npx playwright test --project=screenshots
 */

const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844 };

const OUT = "../docs/screenshots";

// Deliberately fictional. The phone is in the 555-01xx range reserved for
// fiction, so no capture points at anyone's number.
const DEMO = {
  firstName: "Avery",
  lastName: "Sinclair",
  email: "avery.sinclair@example.com",
  phone: "2405550148",
  username: "avery.sinclair",
  password: "Northbank2026",
};

test.describe("auth screenshots", () => {
  test("login — desktop", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    await page.screenshot({ path: `${OUT}/01-login-desktop.png` });
  });

  test("login — mobile", async ({ page }) => {
    await page.setViewportSize(MOBILE);
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    await page.screenshot({ path: `${OUT}/02-login-mobile.png` });
  });

  test("create account — first step", async ({ page }) => {
    // Registration is a wizard now; its remaining steps are captured in
    // onboarding-screenshots.spec.ts.
    await page.setViewportSize(DESKTOP);
    await page.goto("/register");

    await page.getByLabel("Username").fill(DEMO.username);
    await page.getByLabel("Email address").fill(DEMO.email);
    await page.getByLabel("Password", { exact: true }).fill(DEMO.password);
    await page.getByLabel("Confirm password").fill(DEMO.password);

    // Blur the last field so no caret sits in the capture.
    await page.getByRole("heading", { name: /open your northbank account/i }).click();

    await page.screenshot({ path: `${OUT}/03-register-desktop.png` });
  });

  test("create account — validation", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await page.goto("/register");

    await page.getByLabel("Username").fill("av");
    await page.getByLabel("Email address").fill("avery.sinclair.example.com");
    await page.getByLabel("Password", { exact: true }).fill("northbank");
    await page.getByLabel("Confirm password").fill("northbank2");

    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByText("Enter a valid email address")).toBeVisible();

    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `${OUT}/04-register-validation.png`, fullPage: true });
  });

  test("create account — mobile", async ({ page }) => {
    await page.setViewportSize(MOBILE);
    await page.goto("/register");

    await page.getByLabel("Username").fill(DEMO.username);
    await page.getByLabel("Email address").fill(DEMO.email);
    await page.getByLabel("Password", { exact: true }).fill(DEMO.password);

    await page.screenshot({ path: `${OUT}/05-register-mobile.png`, fullPage: true });
  });
});
