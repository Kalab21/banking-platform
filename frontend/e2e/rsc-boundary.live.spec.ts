import { expect, test, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * The Server → Client boundary, against the payload a browser actually gets.
 *
 * The money forms are a Client Component. Everything a Server Component hands
 * one is serialised — into the HTML on a fresh load, and into the RSC/Flight
 * response on a client-side navigation — so a page can mask every label on
 * screen and still publish the raw account number in view-source. That is the
 * defect these tests exist for, and they read the account numbers back from the
 * API for this customer rather than using a fixture, so they keep testing the
 * real thing as the seed changes.
 *
 * Failures never print the number itself.
 *
 *   docker compose up -d
 *   ./scripts/seed-demo.sh
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     E2E_USERNAME=... E2E_PASSWORD=... \
 *     npx playwright test --project=live rsc-boundary
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const USERNAME = process.env.E2E_USERNAME ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "DemoPassword123!";

test.skip(!USERNAME, "Set E2E_USERNAME and E2E_PASSWORD from scripts/seed-demo.sh output.");

test.describe.configure({ timeout: 180_000 });

async function signIn(page: Page, request: import("@playwright/test").APIRequestContext) {
  const auth = await request.post(`${GATEWAY}/api/auth/login`, {
    data: { username: USERNAME, password: PASSWORD },
  });
  const { token, userId } = await auth.json();
  await page.goto("/login");
  await setSessionCookie(page, token);
  return { token, userId, headers: { Authorization: `Bearer ${token}` } };
}

async function settle(page: Page) {
  await page
    .locator('[aria-busy="true"]')
    .waitFor({ state: "detached", timeout: 60_000 })
    .catch(() => {});
  await page.getByRole("heading", { level: 1 }).waitFor({ state: "visible", timeout: 60_000 });
}

/** Asserts without putting the value into the report when it fails. */
function expectAbsent(haystack: string, secret: string, where: string) {
  expect(haystack.includes(secret), `a full account number appears in ${where}`).toBe(false);
}

async function accountsOf(
  request: import("@playwright/test").APIRequestContext,
  userId: number,
  headers: Record<string, string>,
) {
  const accounts = await (
    await request.get(`${GATEWAY}/api/accounts/user/${userId}`, { headers })
  ).json();
  expect(accounts.length, "the seeded customer needs at least one account").toBeGreaterThan(0);
  return accounts as { id: number; accountNumber: string }[];
}

test.describe("account numbers do not cross into the browser", () => {
  test("the money-movement HTML carries no full account number", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);
    const accounts = await accountsOf(request, userId, headers);

    await page.goto("/transactions");
    await settle(page);

    const html = await page.content();
    for (const account of accounts) {
      expectAbsent(html, account.accountNumber, "the delivered HTML of /transactions");
      // The masked form must still be present, or the assertion above would
      // pass on a page that simply failed to render.
      expect(html).toContain(`••••${account.accountNumber.slice(-4)}`);
    }
  });

  test("the RSC payload for a client navigation carries no full account number", async ({
    page,
    request,
  }) => {
    test.slow();
    const { userId, headers } = await signIn(page, request);
    const accounts = await accountsOf(request, userId, headers);

    // Collect every Flight response the router fetches, prefetches included.
    const payloads: string[] = [];
    page.on("response", async (response) => {
      if (!response.url().includes("_rsc=")) return;
      await response
        .text()
        .then((body) => payloads.push(body))
        .catch(() => {
          /* a cancelled prefetch has no body; there is nothing to inspect */
        });
    });

    await page.goto("/dashboard");
    await settle(page);

    // Navigate the way a customer does, so the router fetches a Flight response
    // rather than the server re-rendering a whole document.
    await page
      .getByRole("navigation", { name: "Primary" })
      .getByRole("link", { name: "Transactions", exact: true })
      .first()
      .click();
    await page.waitForURL(/\/transactions$/, { timeout: 90_000 });
    await settle(page);

    expect(
      payloads.length,
      "no RSC responses were captured, so this test checked nothing",
    ).toBeGreaterThan(0);

    const combined = payloads.join("\n");
    for (const account of accounts) {
      expectAbsent(combined, account.accountNumber, "an RSC/Flight response");
    }
  });

  test("the rendered DOM masks the number in text and in metadata alike", async ({
    page,
    request,
  }) => {
    const { userId, headers } = await signIn(page, request);
    const accounts = await accountsOf(request, userId, headers);

    await page.goto("/transactions");
    await settle(page);

    // Hidden elements and metadata count: masking on screen while the value
    // sits in an aria-label is not masking.
    const metadata = await page.evaluate(() =>
      Array.from(document.querySelectorAll("*"))
        .flatMap((element) => [
          element.getAttribute("aria-label"),
          element.getAttribute("title"),
          element.getAttribute("alt"),
          element.getAttribute("value"),
          ...Array.from(element.attributes)
            .filter((attribute) => attribute.name.startsWith("data-"))
            .map((attribute) => attribute.value),
        ])
        .filter(Boolean)
        .join(" | "),
    );
    const text = await page.locator("body").innerText();
    const liveDom = await page.evaluate(() => document.documentElement.outerHTML);

    for (const account of accounts) {
      expectAbsent(text, account.accountNumber, "the rendered text");
      expectAbsent(metadata, account.accountNumber, "an accessibility or data attribute");
      expectAbsent(liveDom, account.accountNumber, "the live DOM");
    }
  });
});
