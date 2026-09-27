import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getAccounts, getOpenFraudAlerts, getLoans, getTransactions } from "@/lib/api/banking";
import { ApiError } from "@/lib/api/errors";

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: () => ({ value: "session-placeholder" }) }),
}));

const fetchMock = vi.fn<typeof fetch>();
beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const respond = (status: number, body = "{}") => fetchMock.mockResolvedValue(new Response(body, { status }));

// An outage used to read as an empty list: "you have no accounts", "no loans",
// and for staff "no open fraud alerts".
describe.each([
  ["accounts", () => getAccounts(7)],
  ["loans", () => getLoans(7)],
  ["fraud alerts", () => getOpenFraudAlerts()],
] as const)("%s", (_name, read) => {
  it.each([500, 502, 504, 400, 409])("rethrows HTTP %s instead of returning an empty list", async (status) => {
    respond(status);
    await expect(read()).rejects.toBeInstanceOf(ApiError);
  });

  it.each([404, 403])("treats HTTP %s as nothing there", async (status) => {
    respond(status);
    await expect(read()).resolves.toEqual([]);
  });
});

describe("transaction history", () => {
  it("returns null, meaning unavailable, when the read fails", async () => {
    respond(500);
    await expect(getTransactions(1)).resolves.toBeNull();
  });

  it("still fails loudly when the session has gone", async () => {
    respond(401);
    await expect(getTransactions(1)).rejects.toBeInstanceOf(ApiError);
  });
});
