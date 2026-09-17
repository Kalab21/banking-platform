import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end configuration.
 *
 * Two projects, deliberately separated:
 *
 * - `offline` runs against the Next.js server with the gateway stubbed at the
 *   network boundary. It needs no backend, so it can run on every CI push and
 *   still exercise the real server actions, cookie handling and redirects.
 * - `live` runs against a fully running stack (`docker compose up -d` plus
 *   `scripts/seed-demo.sh`). It is not part of the default run because it needs
 *   13 services; invoke it explicitly with `npm run test:e2e:live`.
 *
 * Nothing here pretends the live suite runs automatically. See the README.
 */

const PORT = Number(process.env.E2E_PORT ?? 3100);
const BASE_URL = process.env.E2E_BASE_URL ?? `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],

  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    viewport: { width: 1440, height: 900 },
  },

  projects: [
    {
      name: "offline",
      testMatch: /.*\.offline\.spec\.ts/,
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } },
    },
    {
      // Portfolio captures. Reviewed by eye, so kept out of the CI projects and
      // run deliberately: `npx playwright test --project=screenshots`.
      name: "screenshots",
      testMatch: /auth-screenshots\.spec\.ts/,
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } },
    },
    {
      name: "live",
      testMatch: /.*\.live\.spec\.ts/,
      // A real 13-service stack answers more slowly than a stub, especially on
      // the first render after start-up.
      timeout: 90_000,
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } },
    },
  ],

  // The offline project drives a production build with no backend behind it.
  webServer: process.env.E2E_NO_SERVER
    ? undefined
    : {
        command: `npm run start -- --port ${PORT}`,
        url: `${BASE_URL}/login`,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        env: {
          // Points at a port nothing listens on, so stubbed routes are the only
          // way a request can succeed. Any unstubbed call fails loudly.
          API_GATEWAY_URL: "http://127.0.0.1:9",
          NODE_ENV: "production",
        },
      },
});
