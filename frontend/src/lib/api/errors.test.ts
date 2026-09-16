import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "@/lib/api/errors";

/**
 * The mapping from HTTP status to what the user is told.
 *
 * These messages are the whole of the user's explanation when something fails,
 * so they are worth pinning.
 */
describe("ApiError", () => {
  it("prefers the backend's own message when the service supplied one", () => {
    const error = new ApiError(400, "fallback", "/api/transactions/transfer", {
      timestamp: "2026-01-01T00:00:00Z",
      status: 400,
      error: "Bad Request",
      message: "Insufficient funds. Available: 100.00, Requested: 700.00",
      path: "/api/transactions/transfer",
    });

    expect(error.userMessage).toBe("Insufficient funds. Available: 100.00, Requested: 700.00");
  });

  it("explains a 401 as an expired session", () => {
    expect(new ApiError(401, "", "/api/accounts").userMessage).toContain("session has expired");
  });

  it("explains a 403 as a permission problem", () => {
    expect(new ApiError(403, "", "/api/fraud/alerts").userMessage).toContain("permission");
  });

  it("explains a 409 as a conflict", () => {
    expect(new ApiError(409, "", "/api/auth/register").userMessage).toContain("conflicts");
  });

  it("explains a 422 without exposing internals", () => {
    expect(new ApiError(422, "", "/api/payments").userMessage).toContain("could not be processed");
  });

  it("does not blame the user for a 500", () => {
    expect(new ApiError(500, "", "/api/accounts").userMessage).toContain("on our side");
  });

  it("classifies statuses so callers can branch on them", () => {
    expect(new ApiError(401, "", "/x").isUnauthenticated).toBe(true);
    expect(new ApiError(403, "", "/x").isForbidden).toBe(true);
    expect(new ApiError(404, "", "/x").isNotFound).toBe(true);
    expect(new ApiError(404, "", "/x").isUnauthenticated).toBe(false);
  });

  it("carries the status and path for diagnostics", () => {
    const error = new ApiError(404, "Not Found", "/api/loans/99");
    expect(error.status).toBe(404);
    expect(error.path).toBe("/api/loans/99");
    expect(error.name).toBe("ApiError");
  });
});

describe("NetworkError", () => {
  it("distinguishes an unreachable gateway from a rejected request", () => {
    const error = new NetworkError(new Error("ECONNREFUSED"));
    expect(error.userMessage).toContain("Could not reach the banking API");
    expect(error.name).toBe("NetworkError");
  });
});
