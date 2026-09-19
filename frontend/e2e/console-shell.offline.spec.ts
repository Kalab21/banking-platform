import { expect, test, type Page } from "@playwright/test";
import { buildToken, forbidDirectGatewayCalls, setSessionCookie } from "./fixtures";

/**
 * The signed-in shell, with no backend behind it.
 *
 * The gateway is pointed at a dead port in this project, so every page body
 * here reports a failure. That is the point of half these assertions: a bank
 * that cannot reach its accounts service must say so rather than render zeroes,
 * because "$0.00" and "we could not load this" are very different statements to
 * make about somebody's money.
 *
 * The rest is the shell itself — navigation, role filtering, the mobile drawer
 * and layout at small widths — none of which needs real data to be wrong.
 *
 * Flows that need real balances live in `console.live.spec.ts`.
 */

const DESKTOP = { width: 1440, height: 900 };
const PHONE = { width: 390, height: 844 };

async function signIn(page: Page, role: "CUSTOMER" | "EMPLOYEE" | "ADMIN" = "CUSTOMER") {
  await page.goto("/login");
  await setSessionCookie(page, buildToken({ roles: [`ROLE_${role}`], sub: "avery.sinclair" }));
}

async function hasHorizontalOverflow(page: Page): Promise<boolean> {
  return page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
}

test.beforeEach(async ({ page }) => {
  await forbidDirectGatewayCalls(page);
});

test.describe("signed-in navigation", () => {
  test("a customer sees banking navigation and no staff tools", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, "CUSTOMER");
    await page.goto("/dashboard");

    const nav = page.getByRole("navigation", { name: "Primary" });
    for (const label of ["Overview", "Accounts", "Transactions", "Payments", "Credit Cards", "Loans"]) {
      await expect(nav.getByRole("link", { name: label })).toBeVisible();
    }

    // Hiding these is presentation, not protection — the services enforce the
    // rule — but a customer should never be shown a door marked "Fraud Alerts".
    await expect(nav.getByRole("link", { name: "KYC Review" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Fraud Alerts" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Applications" })).toHaveCount(0);
    await expect(nav.getByText("Staff tools")).toHaveCount(0);
  });

  test("an employee keeps the staff tools", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page, "EMPLOYEE");
    await page.goto("/dashboard");

    const nav = page.getByRole("navigation", { name: "Primary" });
    await expect(nav.getByRole("link", { name: "KYC Review" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Fraud Alerts" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Accounts" })).toBeVisible();
  });

  test("the current section is marked, including from a nested route", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page);

    await page.goto("/accounts");
    await expect(
      page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Accounts" }),
    ).toHaveAttribute("aria-current", "page");

    await page.goto("/accounts/42");
    await expect(
      page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Accounts" }),
    ).toHaveAttribute("aria-current", "page");
  });

  test("the sidebar names the signed-in customer", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page);
    await page.goto("/dashboard");

    // The profile call fails in this project, so the shell falls back to the
    // username from the session rather than losing the page.
    await expect(page.getByRole("navigation", { name: "Primary" })).toContainText("avery.sinclair");
    await expect(page.getByRole("button", { name: /sign out/i })).toBeVisible();
  });
});

test.describe("mobile navigation", () => {
  test("opens, closes and reports its state", async ({ page }) => {
    await page.setViewportSize(PHONE);
    await signIn(page);
    await page.goto("/dashboard");

    const toggle = page.getByRole("button", { name: "Open menu" });
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await expect(page.getByRole("link", { name: "Accounts" })).toBeHidden();

    await toggle.click();
    await expect(page.getByRole("button", { name: "Close menu" }).first()).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    await expect(page.getByRole("link", { name: "Accounts" })).toBeVisible();
  });

  test("Escape closes the drawer and returns focus to the control that opened it", async ({
    page,
  }) => {
    await page.setViewportSize(PHONE);
    await signIn(page);
    await page.goto("/dashboard");

    await page.getByRole("button", { name: "Open menu" }).click();
    await expect(page.getByRole("link", { name: "Accounts" })).toBeVisible();

    await page.keyboard.press("Escape");

    await expect(page.getByRole("link", { name: "Accounts" })).toBeHidden();
    // Focus lands back on the toggle, so the next Tab continues from here
    // rather than restarting at the top of the document.
    await expect(page.getByRole("button", { name: "Open menu" })).toBeFocused();
  });

  test("the drawer is reachable and dismissable from the keyboard alone", async ({ page }) => {
    await page.setViewportSize(PHONE);
    await signIn(page);
    await page.goto("/dashboard");

    const toggle = page.getByRole("button", { name: "Open menu" });
    await toggle.focus();
    await page.keyboard.press("Enter");

    await expect(page.getByRole("link", { name: "Accounts" })).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(page.getByRole("link", { name: "Accounts" })).toBeHidden();
  });
});

test.describe("when the gateway cannot be reached", () => {
  /*
   * Each of these pages fails to load its data. None of them may respond by
   * drawing a zero: an account page showing "$0.00" after a failed request has
   * told the customer something false about their balance.
   */
  const pages = [
    { path: "/dashboard", heading: "Overview" },
    { path: "/accounts", heading: "Accounts" },
    { path: "/move-money", heading: "Move money" },
    { path: "/transactions", heading: "Transactions" },
    { path: "/cards", heading: "Credit cards" },
    { path: "/loans", heading: "Loans" },
    { path: "/profile", heading: "Profile & security" },
    { path: "/notifications", heading: "Notifications" },
  ];

  for (const { path, heading } of pages) {
    test(`${path} reports the failure instead of inventing a balance`, async ({ page }) => {
      await page.setViewportSize(DESKTOP);
      await signIn(page);
      await page.goto(path);

      await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
      // Scoped to the page: Next.js renders its own route announcer with
      // role="alert", which is empty and not what is being asserted here.
      // Customer wording, not ours: the screen must not name the gateway.
      await expect(page.locator("main").getByRole("alert")).toContainText(
        /could not reach your accounts/i,
      );
      await expect(page.locator("main")).not.toContainText(/gateway|docker|localhost/i);

      const body = (await page.locator("main").textContent()) ?? "";
      expect(body, "a failed page must not render a currency amount").not.toMatch(
        /\$\s?\d[\d,]*\.\d{2}/,
      );
    });
  }
});

test.describe("signed-in layout", () => {
  for (const width of [1440, 1280, 1024, 768, 430, 390, 320]) {
    test(`the dashboard has no horizontal overflow at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await signIn(page);
      await page.goto("/dashboard");

      expect(await hasHorizontalOverflow(page)).toBe(false);
    });
  }

  for (const path of ["/profile", "/accounts", "/move-money", "/transactions", "/cards", "/loans"]) {
    test(`${path} has no horizontal overflow at 390px`, async ({ page }) => {
      /*
       * A grid or flex item defaults to `min-width: auto`, so a card holding a
       * select with long option text — or a table with a minimum width —
       * refuses to shrink and drags the page wider than the screen. The
       * profile page did exactly that: 658px of content in a 390px viewport,
       * found by driving the real product rather than by looking at it.
       */
      await page.setViewportSize(PHONE);
      await signIn(page);
      await page.goto(path);

      expect(await hasHorizontalOverflow(page)).toBe(false);
    });
  }

  test("the permanent sidebar is not rendered on a phone", async ({ page }) => {
    await page.setViewportSize(PHONE);
    await signIn(page);
    await page.goto("/dashboard");

    // It exists in the DOM but is display:none, so it is out of the
    // accessibility tree and cannot be reached by tabbing behind the app bar.
    await expect(page.getByRole("link", { name: "Overview" })).toBeHidden();
    await expect(page.getByRole("button", { name: "Open menu" })).toBeVisible();
  });

  test("there is exactly one first-level heading on a page", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await signIn(page);
    await page.goto("/dashboard");

    await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
  });
});
