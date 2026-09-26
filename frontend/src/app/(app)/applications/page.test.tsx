import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import ApplicationsPage from "./page";
import { getApplications, getOffers } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/errors";
import type { Application, Offer } from "@/types/api";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/session", () => ({ requireSession: async () => ({ userId: 7 }) }));
vi.mock("@/lib/api/banking", () => ({ getApplications: vi.fn(), getOffers: vi.fn() }));
vi.mock("@/features/credit/actions", () => ({ acceptOfferAction: vi.fn(), declineOfferAction: vi.fn() }));

const application: Application = {
  id: 42, userId: 7, applicationType: "PERSONAL_LOAN", status: "OFFERED",
  requestedAmount: 10000, approvedAmount: null, currency: "USD", termMonths: 36,
  purpose: "Home improvement", creditScoreAtApply: null, annualIncome: 90000,
  monthlyDebtObligations: 0, assetValue: null, downPayment: null, reviewerNotes: null,
  productId: null, appliedAt: null, reviewedAt: null, createdAt: "2026-09-22T00:00:00Z",
};
const offer: Offer = {
  offerId: 9, applicationId: 42, productType: "PERSONAL_LOAN", status: "OFFERED",
  approvedAmount: 8000, apr: 0.075, termMonths: 24, monthlyPayment: 360,
  creditLimit: null, cardTier: null, currency: "USD", createdAt: null, expiresAt: null,
  acceptedAt: null, declinedAt: null,
};

beforeEach(() => {
  vi.mocked(getApplications).mockReset().mockResolvedValue([application]);
  vi.mocked(getOffers).mockReset().mockResolvedValue([offer]);
});

function expectNoActions() {
  expect(screen.queryByRole("button", { name: "Accept offer" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Decline" })).not.toBeInTheDocument();
}

describe("offer visibility and authority", () => {
  it("shows actions only alongside a loaded open offer for an OFFERED application", async () => {
    render(await ApplicationsPage());
    expect(screen.getByRole("button", { name: "Accept offer" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Decline" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Our offer" })).toBeInTheDocument();
    expect(screen.getByText("$8,000.00")).toBeInTheDocument();
    expect(screen.getByText("24 months")).toBeInTheDocument();
    expect(getApplications).toHaveBeenCalledWith(7);
    expect(getOffers).toHaveBeenCalledWith(42);
  });

  it.each(["absent", "backend failure", "network failure"])("blocks actions when an offer is %s", async (failure) => {
    if (failure === "absent") vi.mocked(getOffers).mockResolvedValue([]);
    else vi.mocked(getOffers).mockRejectedValue(failure === "backend failure"
      ? new ApiError(500, "Unavailable", "/api/applications/42/offers")
      : new NetworkError());
    render(await ApplicationsPage());
    expectNoActions();
    expect(screen.getByText("We could not load this offer")).toBeInTheDocument();
    expect(screen.getByText(/terms are not available right now/)).toBeInTheDocument();
    expect(screen.queryByText("Amount", { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText("APR", { exact: true })).not.toBeInTheDocument();
  });

  it.each(["ACCEPTED", "DECLINED", "EXPIRED"] as const)("blocks actions for a %s offer", async (status) => {
    vi.mocked(getOffers).mockResolvedValue([{ ...offer, status }]);
    render(await ApplicationsPage());
    expectNoActions();
    const heading = { ACCEPTED: "The terms you accepted", DECLINED: "The offer you declined", EXPIRED: "This offer has expired" }[status];
    expect(screen.getByRole("heading", { name: heading })).toBeInTheDocument();
  });

  it("never labels a declined offer as accepted", async () => {
    vi.mocked(getApplications).mockResolvedValue([{ ...application, status: "DECLINED" }]);
    vi.mocked(getOffers).mockResolvedValue([{ ...offer, status: "DECLINED" }]);
    render(await ApplicationsPage());
    expect(screen.queryByText("The terms you accepted")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "The offer you declined" })).toBeInTheDocument();
  });

  it.each([
    ["past its expiry date", { expiresAt: "2020-01-01T00:00:00" }],
    ["recorded as EXPIRED", { status: "EXPIRED" as const }],
  ])("tells the truth about an OFFERED application whose offer is %s", async (_case, change) => {
    vi.mocked(getOffers).mockResolvedValue([{ ...offer, ...change }]);
    render(await ApplicationsPage());
    expectNoActions();
    expect(screen.getByRole("heading", { name: "This offer has expired" })).toBeInTheDocument();
    expect(screen.getByText("Offer expired")).toBeInTheDocument();
    expect(screen.queryByText(/yours to accept or decline/)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Apply again" })).toHaveAttribute("href", "/credit");
  });

  it.each(["ACCEPTED", "DECLINED", "PROVISIONING", "PROVISIONED", "CANCELLED"] as const)(
    "blocks actions when the application has moved to %s even if the offer is stale",
    async (status) => {
      vi.mocked(getApplications).mockResolvedValue([{ ...application, status }]);
      render(await ApplicationsPage());
      expectNoActions();
    },
  );

  it("reports offer read failures even after acceptance", async () => {
    vi.mocked(getApplications).mockResolvedValue([{ ...application, status: "ACCEPTED" }]);
    vi.mocked(getOffers).mockRejectedValue(new NetworkError());
    render(await ApplicationsPage());
    expectNoActions();
    expect(screen.getByText("We could not load this offer")).toBeInTheDocument();
  });
});

describe("presentation", () => {
  it("lists the newest application first", async () => {
    vi.mocked(getApplications).mockResolvedValue([
      { ...application, id: 3, applicationType: "CREDIT_CARD", status: "REJECTED" },
      { ...application, id: 42 },
    ]);
    render(await ApplicationsPage());
    const headings = screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);
    expect(headings).toEqual(["Personal loan", "Credit card"]);
  });

  it("shows an expiry date only while the offer is open", async () => {
    vi.mocked(getOffers).mockResolvedValue([{ ...offer, expiresAt: "2999-01-01T00:00:00" }]);
    const { unmount } = render(await ApplicationsPage());
    expect(screen.getByText("Offer valid until")).toBeInTheDocument();
    unmount();

    vi.mocked(getApplications).mockResolvedValue([{ ...application, status: "PROVISIONED", productId: 5 }]);
    vi.mocked(getOffers).mockResolvedValue([{ ...offer, status: "ACCEPTED", expiresAt: "2999-01-01T00:00:00" }]);
    render(await ApplicationsPage());
    expect(screen.queryByText("Offer valid until")).not.toBeInTheDocument();
  });
});

describe("application reads", () => {
  it.each([new ApiError(500, "Unavailable", "/api/applications/user/7"), new NetworkError()])(
    "renders a load error rather than an empty state on failure",
    async (error) => {
      vi.mocked(getApplications).mockRejectedValue(error);
      render(await ApplicationsPage());
      expect(screen.getByText("We could not load your applications")).toBeInTheDocument();
      expect(screen.queryByText("No applications yet")).not.toBeInTheDocument();
      expect(getOffers).not.toHaveBeenCalled();
    },
  );

  it("shows an empty state only for a successfully loaded empty list", async () => {
    vi.mocked(getApplications).mockResolvedValue([]);
    render(await ApplicationsPage());
    expect(screen.getByText("No applications yet")).toBeInTheDocument();
  });
});
