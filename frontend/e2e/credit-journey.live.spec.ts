import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { setSessionCookie } from "./fixtures";

/**
 * The customer credit journey, against a running stack.
 *
 * Explore credit → apply → an offer whose terms are the stored terms → accept
 * or decline → a real product, or none. Every figure the page shows is compared
 * with what the gateway returns for the same application, so terms that look
 * plausible but came from somewhere else fail.
 *
 *   docker compose up -d
 *   ./scripts/seed-demo.sh                      # prints the credentials
 *   E2E_NO_SERVER=1 E2E_BASE_URL=http://localhost:3000 \
 *     E2E_USERNAME=... E2E_PASSWORD=... \
 *     npx playwright test --project=live credit-journey
 *
 * The seeded customer has a completed identity check, so policy decides their
 * applications without referring them. Referral and staff review are proved in
 * e2e-tests.ps1, which holds a reviewer's credentials.
 */

const GATEWAY = process.env.E2E_GATEWAY_URL ?? "http://localhost:8080";
const USERNAME = process.env.E2E_USERNAME ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "DemoPassword123!";

test.skip(!USERNAME, "Set E2E_USERNAME and E2E_PASSWORD from scripts/seed-demo.sh output.");

interface Session {
  userId: number;
  headers: { Authorization: string };
}

interface Application {
  id: number;
  applicationType: string;
  status: string;
  productId: number | null;
}

interface Offer {
  status: string;
  approvedAmount: number | null;
  apr: number | null;
  termMonths: number | null;
  creditLimit: number | null;
  currency: string;
}

function money(amount: number, currency = "USD"): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

async function signIn(page: Page, request: APIRequestContext): Promise<Session> {
  const auth = await request.post(`${GATEWAY}/api/auth/login`, {
    data: { username: USERNAME, password: PASSWORD },
  });
  expect(auth.ok()).toBe(true);
  const { token, userId } = await auth.json();
  await page.goto("/login");
  await setSessionCookie(page, token);
  return { userId, headers: { Authorization: `Bearer ${token}` } };
}

async function applications(request: APIRequestContext, session: Session): Promise<Application[]> {
  const response = await request.get(`${GATEWAY}/api/applications/user/${session.userId}`, {
    headers: session.headers,
  });
  expect(response.ok()).toBe(true);
  return response.json();
}

/** The application this test just submitted: the newest of its type. */
async function newest(request: APIRequestContext, session: Session, type: string, after: number) {
  const mine = (await applications(request, session)).filter(
    (a) => a.applicationType === type && a.id > after,
  );
  expect(mine, `a new ${type} application`).toHaveLength(1);
  return mine[0];
}

async function storedOffer(request: APIRequestContext, session: Session, id: number): Promise<Offer> {
  const response = await request.get(`${GATEWAY}/api/applications/${id}/offers`, {
    headers: session.headers,
  });
  expect(response.ok()).toBe(true);
  const offers: Offer[] = await response.json();
  expect(offers.length).toBeGreaterThan(0);
  return offers[0];
}

async function highestId(request: APIRequestContext, session: Session): Promise<number> {
  return Math.max(0, ...(await applications(request, session)).map((a) => a.id));
}

/** Opens a product's application form the way a customer reaches it. */
async function startApplication(page: Page, product: string) {
  await page.goto("/credit");
  await expect(page.getByRole("heading", { name: "Explore credit" })).toBeVisible();
  await page
    .locator("section")
    .filter({ has: page.getByRole("heading", { name: product, exact: true }) })
    .getByRole("link", { name: "Apply" })
    .click();
  await expect(page.getByRole("heading", { name: `Apply for a ${product.toLowerCase()}` })).toBeVisible();
}

test.describe("customer credit", () => {
  test.describe.configure({ mode: "serial" });

  test("a personal loan: apply, see the stored terms, accept, and reach the real loan", async ({
    page,
    request,
  }) => {
    test.setTimeout(240_000);
    const session = await signIn(page, request);
    const before = await highestId(request, session);

    await startApplication(page, "Personal loan");
    await page.getByLabel("How much would you like to borrow?").fill("4000.00");
    await page.getByLabel("Over how long?").selectOption("24");
    await page.getByLabel("Your annual income before tax").fill("90000.00");
    await page.getByLabel("What you already pay each month towards other debts").fill("450.00");
    await page.getByLabel("What is it for?").fill("Kitchen repair");
    await page.getByRole("button", { name: "Submit application" }).click();
    await page.waitForURL("**/applications");

    const application = await newest(request, session, "PERSONAL_LOAN", before);
    expect(application.status).toBe("OFFERED");
    const offer = await storedOffer(request, session, application.id);
    expect(offer.status).toBe("OFFERED");

    const card = page.locator(`[data-application-id="${application.id}"]`);
    const terms = card.getByRole("region", { name: "Our offer" });
    await expect(terms.getByText(money(offer.approvedAmount!, offer.currency), { exact: true })).toBeVisible();
    await expect(terms.getByText(`${offer.apr!.toFixed(2)}%`, { exact: true })).toBeVisible();
    await expect(terms.getByText(`${offer.termMonths} months`, { exact: true })).toBeVisible();

    await card.getByRole("button", { name: "Accept offer" }).click();

    // Provisioning is a Kafka round trip: the loan service creates the loan and
    // confirms it, and only then does the application carry a product id.
    await expect
      .poll(
        async () =>
          (await applications(request, session)).find((a) => a.id === application.id)?.status,
        { timeout: 180_000, intervals: [2_000] },
      )
      .toBe("PROVISIONED");
    const provisioned = (await applications(request, session)).find((a) => a.id === application.id)!;
    expect(provisioned.productId).toBeGreaterThan(0);

    // A second accept is answered, not acted on: still one loan.
    const again = await request.post(`${GATEWAY}/api/applications/${application.id}/offer/accept`, {
      headers: session.headers,
    });
    expect(again.ok()).toBe(true);
    const loans: { id: number; applicationId: number; principal: number; termMonths: number; interestRate: number }[] =
      await (await request.get(`${GATEWAY}/api/loans/user/${session.userId}`, { headers: session.headers })).json();
    const fromThisApplication = loans.filter((l) => l.applicationId === application.id);
    expect(fromThisApplication).toHaveLength(1);

    // The loan is the accepted offer, figure for figure.
    const loan = fromThisApplication[0];
    expect(loan.id).toBe(provisioned.productId);
    expect(Number(loan.principal)).toBe(Number(offer.approvedAmount));
    expect(loan.termMonths).toBe(offer.termMonths);
    expect(Number(loan.interestRate)).toBe(Number(offer.apr));

    await page.goto("/applications");
    await expect(card.getByRole("heading", { name: "The terms you accepted" })).toBeVisible();
    await expect(card.getByRole("button", { name: "Accept offer" })).toHaveCount(0);
    await card.getByRole("link", { name: "View your personal loan" }).click();
    await page.waitForURL(`**/loans/${provisioned.productId}`);
    // The page is rendered on the server from three service calls; wait for it
    // the way the other live specs do rather than on the 5 s default.
    await expect(page.getByText("Original principal")).toBeVisible({ timeout: 90_000 });
    await expect(page.getByText(money(offer.approvedAmount!, offer.currency)).first()).toBeVisible();
  });

  test("a credit card offer can be declined, and then nothing can be accepted", async ({
    page,
    request,
  }) => {
    const session = await signIn(page, request);
    const before = await highestId(request, session);

    await startApplication(page, "Credit card");
    // A card applicant states nothing about the card: no amount, no term.
    await expect(page.getByLabel("How much would you like to borrow?")).toHaveCount(0);
    await expect(page.getByLabel("Over how long?")).toHaveCount(0);
    await page.getByLabel("Your annual income before tax").fill("90000.00");
    await page.getByLabel("What you already pay each month towards other debts").fill("450.00");
    await page.getByRole("button", { name: "Submit application" }).click();
    await page.waitForURL("**/applications");

    const application = await newest(request, session, "CREDIT_CARD", before);
    expect(application.status).toBe("OFFERED");
    const offer = await storedOffer(request, session, application.id);

    const card = page.locator(`[data-application-id="${application.id}"]`);
    const terms = card.getByRole("region", { name: "Our offer" });
    await expect(terms.getByText(money(offer.creditLimit!, offer.currency), { exact: true })).toBeVisible();
    await expect(terms.getByText(`${offer.apr!.toFixed(2)}%`, { exact: true })).toBeVisible();

    await card.getByRole("button", { name: "Decline" }).click();
    await card.getByRole("button", { name: "Confirm decline" }).click();

    await expect(card.getByRole("heading", { name: "The offer you declined" })).toBeVisible();
    await expect(card.getByRole("button", { name: "Accept offer" })).toHaveCount(0);
    expect((await storedOffer(request, session, application.id)).status).toBe("DECLINED");

    const late = await request.post(`${GATEWAY}/api/applications/${application.id}/offer/accept`, {
      headers: session.headers,
    });
    expect(late.status()).toBe(409);
    const declined = (await applications(request, session)).find((a) => a.id === application.id)!;
    expect(declined.status).toBe("DECLINED");
    expect(declined.productId).toBeNull();
  });

  test("a cardholder freezes and unfreezes their own card", async ({ page, request }) => {
    const session = await signIn(page, request);
    const cards: { id: number; status: string }[] = await (
      await request.get(`${GATEWAY}/api/credit-cards/user/${session.userId}`, { headers: session.headers })
    ).json();
    const active = cards.find((c) => c.status === "ACTIVE");
    test.skip(!active, "The seeded customer has no active card.");
    const status = async () =>
      (
        await (
          await request.get(`${GATEWAY}/api/credit-cards/${active!.id}`, { headers: session.headers })
        ).json()
      ).status;

    try {
      await page.goto(`/cards/${active!.id}`);
      await page.getByRole("button", { name: "Freeze card" }).click();
      await expect(page.getByRole("button", { name: "Unfreeze card" })).toBeVisible();
      expect(await status()).toBe("CUSTOMER_FROZEN");

      await page.getByRole("button", { name: "Unfreeze card" }).click();
      await expect(page.getByRole("button", { name: "Freeze card" })).toBeVisible();
      expect(await status()).toBe("ACTIVE");
    } finally {
      if ((await status()) === "CUSTOMER_FROZEN") {
        await request.put(`${GATEWAY}/api/credit-cards/${active!.id}/status`, {
          headers: session.headers,
          data: { status: "ACTIVE" },
        });
      }
    }
  });

  test("the credit pages fit a phone without sideways scrolling", async ({ page, request }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signIn(page, request);
    for (const path of ["/credit", "/credit/personal-loan", "/applications"]) {
      await page.goto(path);
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow, `${path} overflows by ${overflow}px`).toBeLessThanOrEqual(0);
    }
  });
});
