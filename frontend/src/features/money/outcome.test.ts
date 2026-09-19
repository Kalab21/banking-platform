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

  it("keeps the backend's own wording for a refusal it has no better words for", () => {
    const outcome = classifyMoneyFailure(
      new ApiError(422, "", path, {
        message: "Cannot transfer to the same account",
        status: 422,
        error: "Unprocessable Entity",
        path,
        timestamp: "",
      }),
    );

    expect(outcome.message).toBe("Cannot transfer to the same account");
  });
});

/**
 * The two refusals that arrive written for a log file.
 *
 * Both are shown to a customer who is in the middle of moving money, which is
 * the worst moment to hand someone a bare decimal and a Java enum.
 */
function rejection(message: string, currency?: string) {
  return classifyMoneyFailure(
    new ApiError(422, "", path, {
      message,
      status: 422,
      error: "Unprocessable Entity",
      path,
      timestamp: "",
    }),
    currency,
  );
}

describe("putting a refusal in the customer's language", () => {
  it("states both figures as money rather than as bare decimals", () => {
    const outcome = rejection("Insufficient funds. Available: 164.01, Requested: 250");

    expect(outcome.kind).toBe("rejected");
    expect(outcome.message).toContain("$164.01");
    expect(outcome.message).toContain("$250.00");
    expect(outcome.message).not.toContain("Requested:");
    expect(outcome.message).not.toContain("Insufficient funds.");
  });

  it("says what the customer can do about it", () => {
    expect(rejection("Insufficient funds. Available: 10.00, Requested: 20.00").message).toMatch(
      /smaller amount/i,
    );
  });

  it("quotes the figures in the currency of the account being debited", () => {
    const outcome = rejection("Insufficient funds. Available: 164.01, Requested: 250", "EUR");

    expect(outcome.message).toContain("€164.01");
  });

  it("falls back to dollars rather than throwing on a currency it does not recognise", () => {
    // `Intl.NumberFormat` throws on a bad currency code, and a refusal is the
    // wrong place to turn a display detail into a crash.
    expect(rejection("Insufficient funds. Available: 1.00, Requested: 2.00", "money!").message)
      .toContain("$1.00");
  });

  it("does not leave a status enum in the middle of a sentence", () => {
    const outcome = rejection("Cannot transact on a FROZEN account");

    expect(outcome.message).toContain("frozen");
    expect(outcome.message).not.toContain("FROZEN");
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
