import { describe, expect, it } from "vitest";
import { classifyMoneyFailure, unknownMessage } from "@/features/money/outcome";
import { ApiError, NetworkError } from "@/lib/api/errors";

/**
 * The third outcome.
 *
 * Money movement has a failure mode most forms do not: a request whose result
 * nobody knows. The backend says so precisely — 504 when account-service was
 * reached and the outcome was never established, 500 when a transfer debited
 * and failed to credit, 503 only when the call never left the service — and
 * these tests pin the console to that contract, because the cost of getting it
 * wrong is a customer sending the same money twice.
 */

const path = "/api/transactions/transfer";

describe("classifying a money-movement failure", () => {
  it("treats a 504 as unknown: the debit may have been applied", () => {
    const outcome = classifyMoneyFailure(new ApiError(504, "", path));

    expect(outcome.kind).toBe("unknown");
    expect(outcome.message).toBe(unknownMessage);
  });

  it("treats a partially applied transfer (500) as unknown", () => {
    // The debit landed and the credit did not. Reporting this as a failure
    // would tell the customer their money is where it is not.
    const outcome = classifyMoneyFailure(
      new ApiError(500, "", path, {
        message: "The debit was applied but the credit did not complete.",
        status: 500,
        error: "Internal Server Error",
        path,
        timestamp: "",
      }),
    );

    expect(outcome.kind).toBe("unknown");
  });

  it("treats a transport failure as unknown, because the answer may be what was lost", () => {
    expect(classifyMoneyFailure(new NetworkError()).kind).toBe("unknown");
  });

  it("treats 503 as a rejection, because an open circuit never reached the accounts", () => {
    // The one server status that carries a promise: the call did not leave
    // transaction-service, so no balance was touched.
    expect(classifyMoneyFailure(new ApiError(503, "", path)).kind).toBe("rejected");
  });

  it.each([400, 409, 422])("treats %i as a rejection: nothing was executed", (status) => {
    expect(classifyMoneyFailure(new ApiError(status, "", path)).kind).toBe("rejected");
  });

  it("prefers the backend's own wording when it rejects", () => {
    const outcome = classifyMoneyFailure(
      new ApiError(422, "", path, {
        message: "Insufficient funds. Available: 100.00, Requested: 700.00",
        status: 422,
        error: "Unprocessable Entity",
        path,
        timestamp: "",
      }),
    );

    expect(outcome.message).toContain("Insufficient funds");
  });

  it("never tells the customer to try again when the outcome is unknown", () => {
    // The wording is the control here: an invitation to retry is an invitation
    // to move the money a second time.
    expect(unknownMessage).not.toMatch(/try again|retry|resend/i);
    expect(unknownMessage).toMatch(/transaction history/i);
  });

  it("does not claim success or failure when the outcome is unknown", () => {
    expect(unknownMessage).not.toMatch(/completed|succeeded|failed|could not be completed/i);
  });
});
