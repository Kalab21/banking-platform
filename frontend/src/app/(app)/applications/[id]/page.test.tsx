import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import ApplicationDetailPage from "./page";
import { getApplication, getOffers } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/errors";
import type { Application, Offer } from "@/types/api";

vi.mock("next/navigation", () => ({
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
vi.mock("server-only", () => ({}));
vi.mock("@/lib/session", () => ({ requireSession: async () => ({ userId: 7 }) }));
vi.mock("@/lib/api/banking", () => ({ getApplication: vi.fn(), getOffers: vi.fn() }));
vi.mock("@/features/credit/actions", () => ({ acceptOfferAction: vi.fn(), declineOfferAction: vi.fn() }));

const application: Application = {
  id: 42, userId: 7, applicationType: "PERSONAL_LOAN", status: "OFFERED",
  requestedAmount: 10000, approvedAmount: 8000, currency: "USD", termMonths: 36,
  purpose: "Home improvement", creditScoreAtApply: null, annualIncome: 90000,
  monthlyDebtObligations: 450, assetValue: null, downPayment: null, reviewerNotes: "Internal note",
  productId: null, appliedAt: "2026-09-22T10:00:00", reviewedAt: "2026-09-22T10:00:02",
  createdAt: "2026-09-22T10:00:00",
};
const offer: Offer = {
  offerId: 9, applicationId: 42, productType: "PERSONAL_LOAN", status: "OFFERED",
  approvedAmount: 8000, apr: 7.5, termMonths: 24, monthlyPayment: 360,
  creditLimit: null, cardTier: null, currency: "USD", createdAt: "2026-09-22T10:00:02",
  expiresAt: "2999-01-01T00:00:00", acceptedAt: null, declinedAt: null,
};

const page = (id = "42") => ApplicationDetailPage({ params: Promise.resolve({ id }) });

beforeEach(() => {
  vi.mocked(getApplication).mockReset().mockResolvedValue(application);
  vi.mocked(getOffers).mockReset().mockResolvedValue([offer]);
});

describe("an application's own page", () => {
  it("shows what the customer stated, the offer, and the actions", async () => {
    render(await page());
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Personal loan application");
    expect(screen.getByText("Home improvement")).toBeInTheDocument();
    expect(screen.getByText("$90,000.00")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Our offer" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Accept offer" })).toBeInTheDocument();
    // Staff notes are not the customer's view.
    expect(screen.queryByText("Internal note")).not.toBeInTheDocument();
  });

  it("tells the history from stored events only", async () => {
    render(await page());
    const history = within(screen.getByRole("list", { name: "Application history" }));
    expect(history.getAllByRole("listitem").map((li) => li.getAttribute("data-step"))).toEqual([
      "submitted",
      "decision",
      "offer",
      "answer",
      "product",
    ]);
    expect(history.getByText("Waiting for your answer").closest("li")).toHaveAttribute("aria-current", "step");
  });

  it.each([
    ["missing", new ApiError(404, "Not found", "/api/applications/42")],
    ["someone else's", new ApiError(403, "Forbidden", "/api/applications/42")],
  ])("answers a %s application as not found", async (_case, error) => {
    vi.mocked(getApplication).mockRejectedValue(error);
    await expect(page()).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("does not show another user's application even if the read succeeds", async () => {
    vi.mocked(getApplication).mockResolvedValue({ ...application, userId: 99 });
    await expect(page()).rejects.toThrow("NEXT_NOT_FOUND");
    expect(getOffers).not.toHaveBeenCalled();
  });

  it("refuses an id that is not one", async () => {
    await expect(page("abc")).rejects.toThrow("NEXT_NOT_FOUND");
    expect(getApplication).not.toHaveBeenCalled();
  });

  it("reports a failed read rather than an empty page", async () => {
    vi.mocked(getApplication).mockRejectedValue(new NetworkError());
    render(await page());
    expect(screen.getByText("We could not load this application")).toBeInTheDocument();
  });

  it("offers no actions when the offer cannot be read", async () => {
    vi.mocked(getOffers).mockRejectedValue(new NetworkError());
    render(await page());
    expect(screen.getByText("We could not load this offer")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Accept offer" })).not.toBeInTheDocument();
  });

  it("asks for no offer while an application is still referred", async () => {
    vi.mocked(getApplication).mockResolvedValue({ ...application, status: "MANUAL_REVIEW" });
    render(await page());
    expect(getOffers).not.toHaveBeenCalled();
    expect(screen.getByText("Referred to our team")).toBeInTheDocument();
  });

  it("links to the product once it really exists", async () => {
    vi.mocked(getApplication).mockResolvedValue({ ...application, status: "PROVISIONED", productId: 5 });
    vi.mocked(getOffers).mockResolvedValue([{ ...offer, status: "ACCEPTED", acceptedAt: "2026-09-22T10:05:00" }]);
    render(await page());
    expect(screen.getByRole("link", { name: /View your personal loan/ })).toHaveAttribute("href", "/loans/5");
    expect(screen.getByText("Your personal loan is ready")).toBeInTheDocument();
  });
});
