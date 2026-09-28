import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  payCardAction,
  payOffLoanAction,
  receiveLoanFundsAction,
  repayLoanAction,
} from "@/features/servicing/actions";
import { IDLE } from "@/features/money/state";

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: () => ({ value: "session-placeholder" }) }),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));
vi.mock("@/lib/session", () => ({ requireSession: async () => ({ userId: 7 }) }));

const fetchMock = vi.fn<typeof fetch>();
const KEY = "3f2b8c1e-7a4d-4e5f-9b6a-1c2d3e4f5a6b";

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(fields)) data.set(k, v);
  return data;
}

function answer(body: unknown, status = 200) {
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(body), { status }));
}

function sent() {
  const [url, options] = fetchMock.mock.calls[0];
  const headers = new Headers(options?.headers);
  return {
    path: new URL(String(url)).pathname,
    method: options?.method,
    key: headers.get("Idempotency-Key"),
    body: JSON.parse(options?.body as string),
  };
}

describe("loan and card payments on the wire", () => {
  it.each([
    [repayLoanAction, "loanId", "/api/loans/12/repay", { paymentRef: "LR-1", amount: 180 }],
    [payOffLoanAction, "loanId", "/api/loans/12/payoff", { paymentRef: "LR-2", amount: 180 }],
    [payCardAction, "cardId", "/api/credit-cards/12/payment", { transactionRef: "CT-1", amount: 180 }],
  ] as const)("%o sends the amount, the account and the operation id as its key", async (action, target, path, reply) => {
    answer(reply);
    const result = await action(
      IDLE,
      form({ [target]: "12", amount: "180.00", sourceAccountId: "5", operationId: KEY }),
    );
    expect(sent()).toEqual({ path, method: "POST", key: KEY, body: { amount: 180, sourceAccountId: 5 } });
    expect(result).toMatchObject({ status: "settled", outcome: { kind: "succeeded", amount: 180 } });
  });

  it("reports what the backend took, not what was asked", async () => {
    // A card payment larger than the balance is capped at the balance.
    answer({ transactionRef: "CT-9", amount: 42.5 });
    const result = await payCardAction(
      IDLE,
      form({ cardId: "3", amount: "500.00", sourceAccountId: "5", operationId: KEY }),
    );
    expect(result).toMatchObject({ outcome: { kind: "succeeded", reference: "CT-9", amount: 42.5 } });
  });

  it("refuses a payment without an operation id rather than minting one", async () => {
    const result = await repayLoanAction(IDLE, form({ loanId: "12", amount: "10.00", sourceAccountId: "5" }));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result).toMatchObject({ outcome: { kind: "rejected" } });
  });

  it("refuses an amount finer than a cent before sending anything", async () => {
    const result = await payCardAction(
      IDLE,
      form({ cardId: "3", amount: "10.005", sourceAccountId: "5", operationId: KEY }),
    );
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.status).toBe("invalid");
  });

  it("says the outcome is unknown when the backend could not establish it", async () => {
    answer({ message: "Account service timed out" }, 504);
    const result = await repayLoanAction(
      IDLE,
      form({ loanId: "12", amount: "10.00", sourceAccountId: "5", operationId: KEY }),
    );
    expect(result).toMatchObject({ outcome: { kind: "unknown" } });
  });

  it("passes a refusal on as a refusal", async () => {
    answer({ message: "Insufficient funds. Available: 5.00, Requested: 10.00" }, 422);
    const result = await payOffLoanAction(
      IDLE,
      form({ loanId: "12", amount: "10.00", sourceAccountId: "5", operationId: KEY, currency: "USD" }),
    );
    expect(result).toMatchObject({ outcome: { kind: "rejected" } });
  });
});

describe("receiving a loan's money", () => {
  it("sends only the account, and needs no key because a loan is disbursed once", async () => {
    answer({ id: 12, principal: 4000, status: "ACTIVE" });
    const result = await receiveLoanFundsAction(IDLE, form({ loanId: "12", sourceAccountId: "5" }));
    const call = sent();
    expect(call).toMatchObject({ path: "/api/loans/12/disburse", method: "POST", body: { disbursementAccountId: 5 } });
    expect(call.key).toBeNull();
    expect(result).toMatchObject({ outcome: { kind: "succeeded", amount: 4000 } });
  });

  it("asks for an account before sending anything", async () => {
    const result = await receiveLoanFundsAction(IDLE, form({ loanId: "12", sourceAccountId: "" }));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.status).toBe("invalid");
  });
});
