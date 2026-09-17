import { expect, test, type Page } from "@playwright/test";
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
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  });

  test("the root path routes an anonymous visitor to sign in", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("the onboarding completion screen is not reachable without an account", async ({ page }) => {
    // It ends a flow that issues a session, so it is behind one like every
    // other signed-in page.
    await page.goto("/welcome");
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

  test("the password can be revealed and hidden again", async ({ page }) => {
    await page.goto("/login");

    const password = page.getByLabel("Password", { exact: true });
    await password.fill("Password123");
    await expect(password).toHaveAttribute("type", "password");

    await page.getByRole("button", { name: "Show password" }).click();
    await expect(password).toHaveAttribute("type", "text");

    await page.getByRole("button", { name: "Hide password" }).click();
    await expect(password).toHaveAttribute("type", "password");
  });

  test("the password field is masked and the form is keyboard reachable", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByLabel("Password", { exact: true })).toHaveAttribute("type", "password");

    await page.getByLabel("Username").focus();
    await page.keyboard.press("Tab");
    await expect(page.getByLabel("Password", { exact: true })).toBeFocused();
  });

  test("an unreachable gateway is reported as such, not as bad credentials", async ({ page }) => {
    await page.goto("/login");

    await page.getByLabel("Username").fill("demo.customer");
    await page.getByLabel("Password", { exact: true }).fill("DemoPassword123!");
    await page.getByRole("button", { name: "Sign in" }).click();

    // The server points at a dead port in this project, so this is the
    // gateway-unreachable path rather than a rejected password.
    // Scoped to the form: Next.js renders its own route announcer with role="alert".
    await expect(
      page.locator("form").getByRole("alert"),
    ).toContainText(/could not reach the banking api/i);
  });
});

/**
 * Fills the account step with values that pass, so a test about a later step
 * does not have to restate them.
 */
async function completeAccountStep(page: Page, overrides: Partial<Record<string, string>> = {}) {
  const values = {
    username: "avery.sinclair",
    email: "avery.sinclair@example.com",
    password: "Northbank2026",
    confirmPassword: "Northbank2026",
    ...overrides,
  };

  await page.getByLabel("Username").fill(values.username);
  await page.getByLabel("Email address").fill(values.email);
  await page.getByLabel("Password", { exact: true }).fill(values.password);
  await page.getByLabel("Confirm password").fill(values.confirmPassword);
  await page.getByRole("button", { name: "Continue" }).click();
}

async function completePersonalStep(page: Page, overrides: Partial<Record<string, string>> = {}) {
  const values = {
    firstName: "Avery",
    lastName: "Sinclair",
    dateOfBirth: "1990-01-15",
    phone: "2405550148",
    ...overrides,
  };

  await page.getByLabel("First name").fill(values.firstName);
  await page.getByLabel("Last name").fill(values.lastName);
  await page.getByLabel("Date of birth").fill(values.dateOfBirth);
  await page.getByLabel("Phone number").fill(values.phone);
  await page.getByRole("button", { name: "Continue" }).click();
}

async function completeAddressStep(page: Page, overrides: Partial<Record<string, string>> = {}) {
  const values = {
    streetAddress: "123 Example Street",
    city: "Silver Spring",
    state: "MD",
    postalCode: "20910",
    ...overrides,
  };

  await page.getByLabel("Street address").fill(values.streetAddress);
  await page.getByLabel("City").fill(values.city);
  await page.getByLabel("State").selectOption(values.state);
  await page.getByLabel("ZIP code").fill(values.postalCode);
  await page.getByRole("button", { name: "Continue" }).click();
}

async function completeIdentityStep(page: Page, ssn = "123456789") {
  await page.getByLabel("Social Security number", { exact: true }).fill(ssn);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Continue" }).click();
}

/** Every step filled, stopping on the review screen without submitting. */
async function fillWizardToReview(page: Page) {
  await completeAccountStep(page);
  await completePersonalStep(page);
  await completeAddressStep(page);
  await completeIdentityStep(page);
  await expect(page.getByRole("heading", { name: "Check your details" })).toBeVisible();
}

test.describe("onboarding wizard", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/register");
  });

  test("opens on the first of five steps", async ({ page }) => {
    await expect(page.getByRole("heading", { name: "Create your sign-in" })).toBeVisible();
    await expect(page.getByRole("list", { name: "Step 1 of 5" })).toBeVisible();
  });

  test("will not advance past an empty first step", async ({ page }) => {
    await page.getByRole("button", { name: "Continue" }).click();

    await expect(page.getByText("Username must be at least 3 characters")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Create your sign-in" })).toBeVisible();
  });

  test("rejects an invalid email and a short password before any request", async ({ page }) => {
    await completeAccountStep(page, {
      email: "not-an-email",
      password: "short",
      confirmPassword: "short",
    });

    await expect(page.getByText("Enter a valid email address")).toBeVisible();
    await expect(page.getByText("Password must be at least 8 characters")).toBeVisible();
  });

  test("reports a password that does not match its confirmation", async ({ page }) => {
    await completeAccountStep(page, { confirmPassword: "Northbank2027" });

    await expect(page.getByText("Passwords do not match")).toBeVisible();
  });

  test("ticks off the password rules as they are satisfied", async ({ page }) => {
    await expect(page.getByText("0 of 4 password requirements met")).toBeAttached();

    await page.getByLabel("Password", { exact: true }).fill("Northbank2026");

    await expect(page.getByText("4 of 4 password requirements met")).toBeAttached();
  });

  test("moves to the personal step once the sign-in details are valid", async ({ page }) => {
    await completeAccountStep(page);

    await expect(page.getByRole("heading", { name: "About you" })).toBeVisible();
    await expect(page.getByRole("list", { name: "Step 2 of 5" })).toBeVisible();
  });

  test("refuses a date of birth under eighteen", async ({ page }) => {
    await completeAccountStep(page);
    const recent = new Date();
    recent.setFullYear(recent.getFullYear() - 17);
    await completePersonalStep(page, { dateOfBirth: recent.toISOString().slice(0, 10) });

    await expect(page.getByText("You must be at least 18 to open an account")).toBeVisible();
  });

  test("refuses a date of birth in the future", async ({ page }) => {
    await completeAccountStep(page);
    const future = new Date();
    future.setFullYear(future.getFullYear() + 1);
    await completePersonalStep(page, { dateOfBirth: future.toISOString().slice(0, 10) });

    await expect(page.getByText(/date of birth/i).first()).toBeVisible();
    await expect(page.getByRole("heading", { name: "About you" })).toBeVisible();
  });

  test("formats a phone number as it is typed", async ({ page }) => {
    await completeAccountStep(page);

    const phone = page.getByLabel("Phone number");
    await phone.fill("2405550148");

    await expect(phone).toHaveValue("(240) 555-0148");
  });

  test("requires the parts of an address the backend requires", async ({ page }) => {
    await completeAccountStep(page);
    await completePersonalStep(page);
    await page.getByRole("button", { name: "Continue" }).click();

    await expect(page.getByText("Enter your street address")).toBeVisible();
    await expect(page.getByText("Enter your city")).toBeVisible();
    // Scoped to the error line: "Select a state" is also the placeholder option
    // inside the select itself.
    await expect(page.getByRole("alert").filter({ hasText: "Select a state" })).toBeVisible();
  });

  test("rejects a ZIP code that is not five digits", async ({ page }) => {
    await completeAccountStep(page);
    await completePersonalStep(page);
    await completeAddressStep(page, { postalCode: "209" });

    await expect(page.getByText("Enter a valid 5-digit ZIP code")).toBeVisible();
  });

  test("offers states as a list rather than asking for a code", async ({ page }) => {
    await completeAccountStep(page);
    await completePersonalStep(page);

    await expect(page.getByLabel("State").locator("option", { hasText: "Maryland" })).toHaveCount(1);
  });

  test("groups a Social Security number as it is typed", async ({ page }) => {
    await completeAccountStep(page);
    await completePersonalStep(page);
    await completeAddressStep(page);

    const ssn = page.getByLabel("Social Security number", { exact: true });
    await ssn.fill("123456789");

    await expect(ssn).toHaveValue("123-45-6789");
  });

  test("requires the terms to be accepted", async ({ page }) => {
    await completeAccountStep(page);
    await completePersonalStep(page);
    await completeAddressStep(page);
    await page.getByLabel("Social Security number", { exact: true }).fill("123456789");
    await page.getByRole("button", { name: "Continue" }).click();

    await expect(page.getByText("Accept the terms to continue")).toBeVisible();
  });

  test("reviews what was entered, with the number masked and the password withheld", async ({
    page,
  }) => {
    await fillWizardToReview(page);

    const review = page.locator("form");
    await expect(review).toContainText("avery.sinclair@example.com");
    await expect(review).toContainText("Avery Sinclair");
    await expect(review).toContainText("(240) 555-0148");
    await expect(review).toContainText("123 Example Street");
    await expect(review).toContainText("Maryland");

    // Four digits and no more, because four digits is all the server will keep.
    await expect(review).toContainText("•••-••-6789");
    await expect(review).not.toContainText("123-45-6789");
    await expect(review).not.toContainText("123456789");
    await expect(review).not.toContainText("Northbank2026");
  });

  test("reaching the review screen does not submit the registration", async ({ page }) => {
    /*
     * A regression guard with a specific cause. The step button and the submit
     * button occupy the same place in the layout; rendered without distinct
     * keys they become the same DOM node, and the click that advances from the
     * identity step to review flips that node to type="submit" mid-dispatch.
     * The browser then posts the form, and the customer is registered without
     * ever seeing what they were about to submit.
     */
    const posts: string[] = [];
    page.on("request", (request) => {
      if (request.method() === "POST") posts.push(request.url());
    });

    await fillWizardToReview(page);

    expect(posts, "the wizard posted before the customer submitted it").toEqual([]);
    await expect(page.getByRole("button", { name: "Open my account" })).toBeEnabled();
  });

  test("an edit from the review screen returns straight to the review screen", async ({ page }) => {
    await fillWizardToReview(page);

    await page.getByRole("button", { name: /edit address/i }).click();
    await expect(page.getByRole("heading", { name: "Where you live" })).toBeVisible();

    await page.getByLabel("City").fill("Bethesda");
    await page.getByRole("button", { name: "Back to review" }).click();

    await expect(page.getByRole("heading", { name: "Check your details" })).toBeVisible();
    await expect(page.locator("form")).toContainText("Bethesda");
  });

  test("stepping back keeps what was already entered", async ({ page }) => {
    await completeAccountStep(page);
    await page.getByRole("button", { name: "Back" }).click();

    await expect(page.getByLabel("Username")).toHaveValue("avery.sinclair");
    await expect(page.getByLabel("Email address")).toHaveValue("avery.sinclair@example.com");
  });

  test("nothing from the wizard is written to browser storage", async ({ page }) => {
    // A half-finished onboarding form holds a date of birth, a home address and
    // a Social Security number. None of that may outlive the tab.
    await fillWizardToReview(page);

    const stored = await page.evaluate(() => ({
      local: JSON.stringify(Object.entries(localStorage)),
      session: JSON.stringify(Object.entries(sessionStorage)),
      cookies: document.cookie,
      url: location.href,
    }));

    for (const value of Object.values(stored)) {
      expect(value).not.toContain("123456789");
      expect(value).not.toContain("6789");
      expect(value).not.toContain("Northbank2026");
      expect(value).not.toContain("Example Street");
      expect(value).not.toContain("1990-01-15");
    }
  });

  test("the wizard has no horizontal overflow on a phone at any step", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/register");

    const overflows = async () =>
      page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
      );

    await expect(page.getByText("Step 1 of 5")).toBeVisible();
    expect(await overflows()).toBe(false);

    await completeAccountStep(page);
    expect(await overflows()).toBe(false);

    await completePersonalStep(page);
    expect(await overflows()).toBe(false);

    await completeAddressStep(page);
    expect(await overflows()).toBe(false);

    await completeIdentityStep(page);
    expect(await overflows()).toBe(false);
  });
});

test.describe("signed-out pages", () => {
  /*
   * A page reached before signing in must not look like it is showing an
   * account. A balance or a transaction list on /login tells the visitor that
   * banking information is visible without authentication, and labelling the
   * figures "illustrative" does not undo that — the impression lands before the
   * caption is read.
   */
  const MONEY = /\$\s?\d[\d,]*\.\d{2}/;

  for (const path of ["/login", "/register"]) {
    test(`${path} shows no balance, account number or transaction`, async ({ page }) => {
      await page.goto(path);
      const body = (await page.locator("body").textContent()) ?? "";

      expect(body).not.toMatch(MONEY);
      expect(body).not.toMatch(/available balance/i);
      expect(body).not.toMatch(/everyday checking/i);
      expect(body).not.toMatch(/direct deposit/i);
      expect(body).not.toMatch(/···· \d{4}/);
    });
  }

  test("the sign-in page carries no account figures in its delivered HTML", async ({ page }) => {
    const response = await page.goto("/login");
    const html = (await response?.text()) ?? "";

    expect(html).not.toContain("24,850.75");
    expect(html).not.toMatch(/illustrative figures/i);
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

  test("the desktop sign-in page shows the brand panel", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/login");

    await expect(page.getByRole("heading", { name: /bank securely/i })).toBeVisible();
  });

  test("the brand panel is hidden on a phone, so the form comes first", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/login");

    await expect(page.getByRole("heading", { name: /bank securely/i })).toBeHidden();
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  });

  test("the create-account page has no horizontal overflow on a phone", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/register");

    const overflows = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    );
    expect(overflows).toBe(false);
  });

  test("the sign-in page still fits a 320px viewport", async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 720 });
    await page.goto("/login");

    const overflows = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    );
    expect(overflows).toBe(false);
  });
});
