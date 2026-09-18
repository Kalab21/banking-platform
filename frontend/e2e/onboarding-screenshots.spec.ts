import { expect, test } from "@playwright/test";
import {
  fillAccountStep,
  fillAddressStep,
  fillIdentityStep,
  fillPersonalStep,
  syntheticApplicant,
} from "./onboarding";

/**
 * Portfolio screenshots of onboarding.
 *
 * Run on demand rather than in CI — they write into docs/screenshots and are
 * reviewed by eye, which a pipeline cannot assert.
 *
 * Every value typed here is fictional: `example.com`, the `555-01xx` range
 * reserved for fiction, and a Social Security number reserved for demonstration
 * use. The number is masked on screen by the field itself, so no capture can
 * contain one whatever is typed — the last frame checks that rather than
 * assuming it.
 *
 *   npx playwright test --project=screenshots onboarding-screenshots
 */

const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844 };

const OUT = "../docs/screenshots";

const DEMO = syntheticApplicant({
  username: "avery.sinclair",
  email: "avery.sinclair@example.com",
});

const advance = (page: import("@playwright/test").Page) =>
  page.getByRole("button", { name: "Continue" }).click();

/** Moves focus somewhere inert so no caret sits blinking in the capture. */
const settle = (page: import("@playwright/test").Page) =>
  page.getByRole("heading", { name: /open your northbank account/i }).click();

test.describe("onboarding screenshots", () => {
  test("step 1 — sign-in details", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await page.goto("/register");

    await fillAccountStep(page, DEMO);
    await settle(page);

    await page.screenshot({ path: `${OUT}/07-onboarding-account.png` });
  });

  test("step 2 — personal details", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await page.goto("/register");

    await fillAccountStep(page, DEMO);
    await advance(page);
    await fillPersonalStep(page, DEMO);
    await settle(page);

    await page.screenshot({ path: `${OUT}/08-onboarding-personal.png` });
  });

  test("step 3 — address", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await page.goto("/register");

    await fillAccountStep(page, DEMO);
    await advance(page);
    await fillPersonalStep(page, DEMO);
    await advance(page);
    await fillAddressStep(page, DEMO);
    await settle(page);

    await page.screenshot({ path: `${OUT}/09-onboarding-address.png` });
  });

  test("step 4 — identity, with the number masked", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await page.goto("/register");

    await fillAccountStep(page, DEMO);
    await advance(page);
    await fillPersonalStep(page, DEMO);
    await advance(page);
    await fillAddressStep(page, DEMO);
    await advance(page);
    await fillIdentityStep(page, DEMO);
    await settle(page);

    // The field masks its own value, so the capture cannot show the number.
    // Asserted rather than assumed, because this is the frame where a mistake
    // would put one into a file that gets committed.
    const ssn = page.getByLabel("Social Security number", { exact: true });
    await expect(ssn).toHaveAttribute("type", "password");

    await page.screenshot({ path: `${OUT}/10-onboarding-identity.png` });
  });

  test("step 5 — review", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await page.goto("/register");

    await fillAccountStep(page, DEMO);
    await advance(page);
    await fillPersonalStep(page, DEMO);
    await advance(page);
    await fillAddressStep(page, DEMO);
    await advance(page);
    await fillIdentityStep(page, DEMO);
    await advance(page);

    await expect(page.getByRole("heading", { name: "Check your details" })).toBeVisible();

    // Four digits, and no password anywhere on the summary.
    const form = page.locator("form");
    await expect(form).toContainText(`•••-••-${DEMO.ssnLast4}`);
    await expect(form).not.toContainText(DEMO.ssn);
    await expect(form).not.toContainText(DEMO.password);

    await page.screenshot({ path: `${OUT}/11-onboarding-review.png`, fullPage: true });
  });

  test("onboarding on a phone", async ({ page }) => {
    await page.setViewportSize(MOBILE);
    await page.goto("/register");

    await fillAccountStep(page, DEMO);
    await advance(page);
    await fillPersonalStep(page, DEMO);
    await settle(page);

    await expect(page.getByText("Step 2 of 5")).toBeVisible();

    await page.screenshot({ path: `${OUT}/12-onboarding-mobile.png`, fullPage: true });
  });
});
