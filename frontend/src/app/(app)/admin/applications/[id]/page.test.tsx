import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import AdminApplicationPage from "./page";
import { getApplication, getApplicationDecisions, getOffers, getUser } from "@/lib/api/banking";
import type { Application, DecisionSnapshot, UserProfile } from "@/types/api";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/session", () => ({ requireStaffSession: async () => ({ userId: 99, role: "EMPLOYEE" }) }));
vi.mock("@/lib/api/banking", () => ({
  getApplication: vi.fn(), getApplicationDecisions: vi.fn(), getOffers: vi.fn(), getUser: vi.fn(),
}));
vi.mock("@/features/admin/review-actions", () => ({ reviewApplicationAction: vi.fn() }));

const application: Application = {
  id: 42, userId: 7, applicationType: "PERSONAL_LOAN", status: "MANUAL_REVIEW",
  requestedAmount: 10000, approvedAmount: null, currency: "USD", termMonths: 36,
  purpose: "Home improvement", creditScoreAtApply: 700, annualIncome: 90000,
  monthlyDebtObligations: 450, assetValue: null, downPayment: null, reviewerNotes: null,
  productId: null, appliedAt: "2026-09-27T10:00:00", reviewedAt: null, createdAt: "2026-09-27T10:00:00",
};
const decision = (over: Partial<DecisionSnapshot>): DecisionSnapshot => ({
  decisionId: 1, applicationId: 42, policyVersion: "2026.09", decidedBy: "POLICY", reviewerId: null,
  decision: "REFER", creditScoreAtDecision: 700, kycStatusAtDecision: "IN_REVIEW",
  annualIncomeAtDecision: 90000, monthlyDebtAtDecision: 450, dtiAtDecision: 0.06, ltvAtDecision: null,
  assetValueAtDecision: null, requestedAmountAtDecision: 10000, requestedTermAtDecision: 36,
  approvedAmount: null, reasonCodes: ["KYC_REVIEW_REQUIRED"], decidedAt: "2026-09-27T10:00:01", ...over,
});

beforeEach(() => {
  vi.mocked(getApplication).mockResolvedValue(application);
  vi.mocked(getApplicationDecisions).mockResolvedValue([decision({})]);
  vi.mocked(getOffers).mockResolvedValue([]);
  vi.mocked(getUser).mockResolvedValue({ id: 7, firstName: "Alex", lastName: "Morgan", kycStatus: "IN_REVIEW" } as UserProfile);
});

const load = async () => render(await AdminApplicationPage({ params: Promise.resolve({ id: "42" }) }));

describe("staff application workbench", () => {
  it("shows the stored evidence and the policy's reasons exactly", async () => {
    await load();
    expect(screen.getByText("Policy 2026.09")).toBeInTheDocument();
    expect(screen.getByText("KYC_REVIEW_REQUIRED")).toBeInTheDocument();
    expect(screen.getByText("$90,000.00")).toBeInTheDocument();
    expect(screen.getByText("6.00%")).toBeInTheDocument();
  });

  it("offers approve and reject, and no refer, while referred", async () => {
    await load();
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /refer/i })).not.toBeInTheDocument();
  });

  it("keeps the policy's referral beside the reviewer's approval, and offers no decision after", async () => {
    vi.mocked(getApplication).mockResolvedValue({ ...application, status: "OFFERED" });
    vi.mocked(getApplicationDecisions).mockResolvedValue([
      decision({}),
      decision({ decisionId: 2, decidedBy: "REVIEWER", reviewerId: 99, decision: "APPROVE", reasonCodes: ["MANUAL_REVIEW_REQUIRED"] }),
    ]);
    await load();
    expect(screen.getByText("Policy 2026.09")).toBeInTheDocument();
    expect(screen.getByText("Reviewer #99")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });
});
