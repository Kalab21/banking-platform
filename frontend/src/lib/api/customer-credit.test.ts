import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { acceptOffer, declineOffer, getApplications, getOffers, submitApplication } from "@/lib/api/banking";
import { setCardFreezeAction } from "@/features/cards/actions";
import { ApiError, NetworkError } from "@/lib/api/errors";
import type { CreateApplicationRequest } from "@/types/api";

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: () => ({ value: "session-placeholder" }) }),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));
vi.mock("@/lib/session", () => ({ requireSession: async () => ({ userId: 7 }) }));

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

// Exercise the real apiFetch serialization, not a mock of its options.
describe("customer credit requests on the wire", () => {
  it("sends an application as one JSON object", async () => {
    const request: CreateApplicationRequest = {
      userId: 7,
      applicationType: "PERSONAL_LOAN",
      currency: "USD",
      requestedAmount: 10000,
      termMonths: 36,
      purpose: "Home improvement",
      annualIncome: 90000,
      monthlyDebtObligations: 0,
    };
    await submitApplication(request);
    const [url, options] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/applications");
    expect(options?.method).toBe("POST");
    expect(JSON.parse(options?.body as string)).toEqual(request);
  });

  it.each([
    ["freeze", "CUSTOMER_FROZEN"],
    ["unfreeze", "ACTIVE"],
  ])("sends %s as one JSON status object", async (intent, status) => {
    const form = new FormData();
    form.set("cardId", "42");
    form.set("intent", intent);
    const result = await setCardFreezeAction({}, form);
    expect(result.error).toBeUndefined();
    expect(result.success).toBeDefined();
    const [url, options] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/credit-cards/42/status");
    expect(options?.method).toBe("PUT");
    expect(JSON.parse(options?.body as string)).toEqual({ status });
  });

  it("sends nothing for an intent other than freeze or unfreeze", async () => {
    const form = new FormData();
    form.set("cardId", "42");
    form.set("intent", "close");
    const result = await setCardFreezeAction({}, form);
    expect(result.error).toBeDefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ["accept", acceptOffer],
    ["decline", declineOffer],
  ] as const)("sends %s without editable offer terms", async (intent, act) => {
    await act(42);
    const [url, options] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe(`/api/applications/42/offer/${intent}`);
    expect(options?.method).toBe("POST");
    expect(options?.body).toBeUndefined();
  });
});

describe.each([
  ["applications", getApplications],
  ["offers", getOffers],
] as const)("strict %s reads", (_name, read) => {
  it("preserves a successful empty response", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await expect(read(7)).resolves.toEqual([]);
  });

  it.each([403, 404, 500])("propagates HTTP %s rather than inventing an empty list", async (status) => {
    fetchMock.mockResolvedValue(new Response("{}", { status }));
    await expect(read(7)).rejects.toMatchObject({ status });
    await expect(read(7)).rejects.toBeInstanceOf(ApiError);
  });

  it("propagates network failures", async () => {
    fetchMock.mockRejectedValue(new TypeError("Gateway unavailable"));
    await expect(read(7)).rejects.toBeInstanceOf(NetworkError);
  });
});
