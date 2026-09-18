import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * Money movement, driven through the console against the real stack.
 *
 * Every figure here is read back from the gateway in the same test. The
 * question each one answers is not "did the page say it worked" but "did the
 * balance move, once, by the amount the customer confirmed".
 *
 *   docker compose up -d
 *   ./scripts/seed-demo.sh
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     E2E_USERNAME=... E2E_PASSWORD=... \
 *     npx playwright test --project=live move-money
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const USERNAME = process.env.E2E_USERNAME ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "DemoPassword123!";

test.skip(!USERNAME, "Set E2E_USERNAME and E2E_PASSWORD from scripts/seed-demo.sh output.");

test.describe.configure({ timeout: 240_000, mode: "serial" });

interface Account {
  id: number;
  accountNumber: string;
  balance: number;
  currency: string;
  accountType: string;
}

function money(amount: number, currency = "USD"): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

async function signIn(page: Page, request: APIRequestContext) {
  const auth = await request.post(`${GATEWAY}/api/auth/login`, {
    data: { username: USERNAME, password: PASSWORD },
  });
  const { token, userId } = await auth.json();
  await page.goto("/login");
  await setSessionCookie(page, token);
  return { token, userId, headers: { Authorization: `Bearer ${token}` } };
}

async function accountsOf(
  request: APIRequestContext,
  userId: number,
  headers: Record<string, string>,
): Promise<Account[]> {
  const response = await request.get(`${GATEWAY}/api/accounts/user/${userId}`, { headers });
  return response.json();
}

async function settle(page: Page) {
  await page
    .locator('[aria-busy="true"]')
    .waitFor({ state: "detached", timeout: 90_000 })
    .catch(() => {});
  await page.getByRole("heading", { level: 1 }).waitFor({ state: "visible", timeout: 90_000 });
}

/** Counts transactions on an account, so "moved once" can be asserted as once. */
async function transactionCount(
  request: APIRequestContext,
  accountId: number,
  headers: Record<string, string>,
): Promise<number> {
  const response = await request.get(
    `${GATEWAY}/api/transactions/account/${accountId}?page=0&size=100`,
    { headers },
  );
  const page = await response.json();
  return page?.totalElements ?? page?.content?.length ?? 0;
}

test.describe("moving money through the console", () => {
  test("a deposit goes details, review, receipt — and lands once", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);
    const before = await accountsOf(request, userId, headers);
    const account = before[0];
    const countBefore = await transactionCount(request, account.id, headers);
    const amount = 12.34;

    await page.goto("/move-money");
    await settle(page);

    await page.getByRole("tab", { name: "Deposit" }).click();
    await page.getByLabel("Account").selectOption(String(account.id));
    await page.getByLabel("Amount").fill(String(amount));
    await page.getByLabel("Description").fill("E2E deposit");
    await page.getByRole("button", { name: /review deposit/i }).click();

    // The review names the money and the account, masked.
    const review = page.getByTestId("review-panel");
    await expect(review).toContainText(money(amount, account.currency));
    await expect(review).toContainText(`••••${account.accountNumber.slice(-4)}`);
    await expect(review).not.toContainText(account.accountNumber);

    await page.getByRole("button", { name: /confirm deposit/i }).click();

    const receipt = page.getByTestId("receipt");
    await expect(receipt).toBeVisible({ timeout: 120_000 });
    await expect(receipt).toContainText("Deposit complete.");
    await expect(receipt).toContainText(money(amount, account.currency));
    await expect(receipt).not.toContainText(account.accountNumber);

    const after = await accountsOf(request, userId, headers);
    const moved = after.find((a) => a.id === account.id)!;
    expect(Number((moved.balance - account.balance).toFixed(2))).toBe(amount);
    expect(await transactionCount(request, account.id, headers)).toBe(countBefore + 1);
  });

  test("a withdrawal shows the available balance, then debits once", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);
    const before = await accountsOf(request, userId, headers);
    const account = before[0];
    const countBefore = await transactionCount(request, account.id, headers);
    const amount = 7.5;

    await page.goto("/move-money");
    await settle(page);

    await page.getByRole("tab", { name: "Withdraw" }).click();
    await page.getByLabel("Account").selectOption(String(account.id));
    await page.getByLabel("Amount").fill(String(amount));
    await page.getByRole("button", { name: /review withdraw/i }).click();

    // The figure the decision turns on is on the review, from the API.
    await expect(page.getByTestId("review-panel")).toContainText("Available");

    await page.getByRole("button", { name: /confirm withdraw/i }).click();
    await expect(page.getByTestId("receipt")).toBeVisible({ timeout: 120_000 });
    await expect(page.getByTestId("receipt")).toContainText("Withdrawal complete.");

    const after = await accountsOf(request, userId, headers);
    const moved = after.find((a) => a.id === account.id)!;
    expect(Number((account.balance - moved.balance).toFixed(2))).toBe(amount);
    expect(await transactionCount(request, account.id, headers)).toBe(countBefore + 1);
  });

  test("a transfer debits the source once and credits the destination once", async ({
    page,
    request,
  }) => {
    const { userId, headers } = await signIn(page, request);
    const before = await accountsOf(request, userId, headers);
    test.skip(before.length < 2, "a transfer needs two accounts");

    const [source, destination] = before;
    const sourceCountBefore = await transactionCount(request, source.id, headers);
    const destinationCountBefore = await transactionCount(request, destination.id, headers);
    const amount = 5.25;

    await page.goto("/move-money");
    await settle(page);

    await page.getByLabel("From").selectOption(String(source.id));
    await page.getByLabel("To").selectOption(String(destination.id));
    await page.getByLabel("Amount").fill(String(amount));
    await page.getByRole("button", { name: /review transfer/i }).click();

    const review = page.getByTestId("review-panel");
    await expect(review).toContainText(`••••${source.accountNumber.slice(-4)}`);
    await expect(review).toContainText(`••••${destination.accountNumber.slice(-4)}`);

    await page.getByRole("button", { name: /confirm transfer/i }).click();
    await expect(page.getByTestId("receipt")).toBeVisible({ timeout: 120_000 });
    await expect(page.getByTestId("receipt")).toContainText("Transfer complete.");

    const after = await accountsOf(request, userId, headers);
    const movedFrom = after.find((a) => a.id === source.id)!;
    const movedTo = after.find((a) => a.id === destination.id)!;

    expect(Number((source.balance - movedFrom.balance).toFixed(2))).toBe(amount);
    expect(Number((movedTo.balance - destination.balance).toFixed(2))).toBe(amount);
    expect(await transactionCount(request, source.id, headers)).toBe(sourceCountBefore + 1);
    expect(await transactionCount(request, destination.id, headers)).toBe(
      destinationCountBefore + 1,
    );
  });

  test("a double click on confirm moves the money once", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);
    const before = await accountsOf(request, userId, headers);
    const account = before[0];
    const countBefore = await transactionCount(request, account.id, headers);
    const amount = 3.21;

    await page.goto("/move-money");
    await settle(page);

    await page.getByRole("tab", { name: "Deposit" }).click();
    await page.getByLabel("Account").selectOption(String(account.id));
    await page.getByLabel("Amount").fill(String(amount));
    await page.getByRole("button", { name: /review deposit/i }).click();

    const confirm = page.getByRole("button", { name: /confirm deposit/i });
    // Two clicks as fast as the browser will deliver them.
    await confirm.click();
    await confirm.click({ force: true, timeout: 2_000 }).catch(() => {
      /* the button is disabled while pending, which is the point */
    });

    await expect(page.getByTestId("receipt")).toBeVisible({ timeout: 120_000 });

    const after = await accountsOf(request, userId, headers);
    const moved = after.find((a) => a.id === account.id)!;
    expect(Number((moved.balance - account.balance).toFixed(2))).toBe(amount);
    expect(await transactionCount(request, account.id, headers)).toBe(countBefore + 1);
  });

  test("the receipt's reference is the one the API recorded", async ({ page, request }) => {
    const { userId, headers } = await signIn(page, request);
    const before = await accountsOf(request, userId, headers);
    const account = before[0];

    await page.goto("/move-money");
    await settle(page);

    await page.getByRole("tab", { name: "Deposit" }).click();
    await page.getByLabel("Account").selectOption(String(account.id));
    await page.getByLabel("Amount").fill("1.11");
    await page.getByRole("button", { name: /review deposit/i }).click();
    await page.getByRole("button", { name: /confirm deposit/i }).click();
    await expect(page.getByTestId("receipt")).toBeVisible({ timeout: 120_000 });

    const shown = (await page.getByTestId("receipt").innerText()).split("\n");
    const reference = shown[shown.length - 1].trim();

    const history = await (
      await request.get(`${GATEWAY}/api/transactions/account/${account.id}?page=0&size=5`, {
        headers,
      })
    ).json();
    const refs = history.content.map((t: { transactionRef: string }) => t.transactionRef);
    expect(refs, "the receipt shows a reference the API actually issued").toContain(reference);
  });

  test("transactions is history, and move money is the action", async ({ page, request }) => {
    await signIn(page, request);

    await page.goto("/transactions");
    await settle(page);

    // The forms moved out; what is left is the record.
    await expect(page.getByRole("heading", { level: 1, name: "Transactions" })).toBeVisible();
    await expect(page.getByTestId("transfer-form")).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Move money" })).toBeVisible();

    await page.getByRole("link", { name: "Move money" }).click();
    await page.waitForURL(/\/move-money$/, { timeout: 90_000 });
    await settle(page);
    await expect(page.getByRole("tab", { name: "Transfer" })).toBeVisible();
  });
});
