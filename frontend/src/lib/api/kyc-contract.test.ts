import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { reviewKycDocument, setKycStatus } from "@/lib/api/banking";

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: () => ({ value: "session-placeholder" }) }),
}));

const fetchMock = vi.fn<typeof fetch>();
beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

describe("KYC requests on the wire", () => {
  it("sends a document review as ReviewDocumentRequest, with no reviewer id", async () => {
    await reviewKycDocument(5, "REJECTED", "Photo unreadable");
    const [url, options] = fetchMock.mock.calls[0];
    const parsed = new URL(String(url));
    expect(parsed.pathname).toBe("/api/kyc/documents/5/review");
    expect(parsed.search).toBe("");
    expect(options?.method).toBe("PUT");
    expect(JSON.parse(options?.body as string)).toEqual({ status: "REJECTED", rejectionReason: "Photo unreadable" });
  });

  it("sets a customer's identity status by query parameter", async () => {
    await setKycStatus(10, "APPROVED");
    const [url, options] = fetchMock.mock.calls[0];
    const parsed = new URL(String(url));
    expect(parsed.pathname).toBe("/api/users/10/kyc/status");
    expect(parsed.searchParams.get("status")).toBe("APPROVED");
    expect(options?.method).toBe("PUT");
  });
});
