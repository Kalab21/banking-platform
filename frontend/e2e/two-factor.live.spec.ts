import { expect, test } from "@playwright/test";

/**
 * Signing in to an account with TOTP enrolled stops at the second-factor
 * challenge instead of issuing a session.
 *
 * The challenge only appears when the backend answers `twoFactorRequired`, so
 * it cannot be produced by the offline project, which has no gateway behind it.
 * Requires a demo account with TOTP already enrolled, named by E2E_2FA_USERNAME.
 */
test("an enrolled account is challenged for its second factor", async ({ page }) => {
  const username = process.env.E2E_2FA_USERNAME;
  test.skip(!username, "E2E_2FA_USERNAME not set");

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/login");

  await page.getByLabel("Username").fill(username!);
  await page.getByLabel("Password", { exact: true }).fill("Northbank2026");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page.getByRole("heading", { name: /verify it's you/i })).toBeVisible({
    timeout: 60_000,
  });
});
