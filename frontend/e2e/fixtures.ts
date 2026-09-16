import type { Page, Route } from "@playwright/test";

/**
 * Gateway stubs for the offline suite.
 *
 * The Next.js server makes these calls, not the browser, so they are intercepted
 * at the server's own network boundary via Playwright's `page.route` on the
 * server process is not possible — instead the offline suite points the server
 * at a dead port and asserts on how the console behaves when the gateway cannot
 * answer, plus the parts of the flow that never reach the gateway at all
 * (validation, redirects, cookie handling).
 *
 * Anything that needs real banking data lives in the `live` suite instead, where
 * the actual services answer.
 */

/** An obviously fake, expired-looking token for cookie-handling tests. */
export const EXPIRED_TOKEN = buildToken({ exp: 1_600_000_000 });

/** A syntactically valid token that will not be honoured by any real gateway. */
export function buildToken(overrides: Record<string, unknown> = {}): string {
  const encode = (obj: unknown) =>
    Buffer.from(JSON.stringify(obj))
      .toString("base64")
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");

  const payload = {
    sub: "demo.customer",
    userId: 1,
    roles: ["ROLE_CUSTOMER"],
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + 3600,
    ...overrides,
  };

  return `${encode({ alg: "HS256", typ: "JWT" })}.${encode(payload)}.not-a-real-signature`;
}

/** Plants a session cookie directly, bypassing the login form. */
export async function setSessionCookie(page: Page, token: string): Promise<void> {
  const url = new URL(page.url() === "about:blank" ? "http://127.0.0.1:3100" : page.url());
  await page.context().addCookies([
    {
      name: "bp_session",
      value: token,
      domain: url.hostname,
      path: "/",
      httpOnly: true,
      sameSite: "Lax",
    },
  ]);
}

/** Fails the test loudly if the page ever calls the gateway from the browser. */
export async function forbidDirectGatewayCalls(page: Page): Promise<void> {
  await page.route("**://*:8080/**", (route: Route) => {
    throw new Error(
      `The browser attempted to call the API Gateway directly: ${route.request().url()}`,
    );
  });
}
