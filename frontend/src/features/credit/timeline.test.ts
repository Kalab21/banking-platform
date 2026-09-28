import { describe, expect, it } from "vitest";
import { applicationTimeline, offerSummary } from "@/features/credit/timeline";
import type { Application, Offer } from "@/types/api";

const NOW = new Date("2026-09-25T00:00:00Z");

const app = (over: Partial<Application> = {}): Application => ({
  id: 1, userId: 7, applicationType: "CREDIT_CARD", status: "OFFERED", requestedAmount: null,
  approvedAmount: null, currency: "USD", termMonths: null, purpose: null, creditScoreAtApply: null,
  annualIncome: 72000, monthlyDebtObligations: 300, assetValue: null, downPayment: null,
  reviewerNotes: null, productId: null, appliedAt: "2026-09-20T09:00:00", reviewedAt: "2026-09-20T09:00:01",
  createdAt: "2026-09-20T09:00:00", ...over,
});

const offer = (over: Partial<Offer> = {}): Offer => ({
  offerId: 3, applicationId: 1, productType: "CREDIT_CARD", status: "OFFERED", approvedAmount: null,
  apr: 21.99, termMonths: null, monthlyPayment: null, creditLimit: 3000, cardTier: "STANDARD",
  currency: "USD", createdAt: "2026-09-20T09:00:01", expiresAt: "2026-10-20T09:00:01",
  acceptedAt: null, declinedAt: null, ...over,
});

const labels = (a: Application, o?: Offer) => applicationTimeline(a, o, NOW).map((s) => [s.label, s.state]);

describe("application timeline", () => {
  it("stops at assessment while no decision exists", () => {
    expect(labels(app({ status: "UNDER_REVIEW", reviewedAt: null }))).toEqual([
      ["Application submitted", "done"],
      ["Being assessed", "current"],
    ]);
  });

  it("ends at the refusal", () => {
    expect(labels(app({ status: "REJECTED" }))).toEqual([
      ["Application submitted", "done"],
      ["Not approved", "done"],
    ]);
  });

  it("waits on the customer while the offer is open, and shows the product as not yet", () => {
    expect(labels(app(), offer())).toEqual([
      ["Application submitted", "done"],
      ["Approved", "done"],
      ["Offer made", "done"],
      ["Waiting for your answer", "current"],
      ["Your credit card is set up", "upcoming"],
    ]);
  });

  it("marks an offer past its date as expired even while it still reads OFFERED", () => {
    const steps = applicationTimeline(app(), offer({ expiresAt: "2026-09-21T00:00:00" }), NOW);
    expect(steps.at(-1)).toMatchObject({ label: "Offer expired", at: "2026-09-21T00:00:00" });
  });

  it("uses the stored acceptance time, and no invented one for provisioning", () => {
    const steps = applicationTimeline(
      app({ status: "PROVISIONED", productId: 8 }),
      offer({ status: "ACCEPTED", acceptedAt: "2026-09-20T09:05:00" }),
      NOW,
    );
    expect(steps.slice(-2)).toEqual([
      { id: "answer", label: "You accepted the offer", at: "2026-09-20T09:05:00", state: "done" },
      { id: "product", label: "Your credit card is ready", at: null, state: "done" },
    ]);
  });

  it("ends at a decline", () => {
    const steps = applicationTimeline(
      app({ status: "DECLINED" }),
      offer({ status: "DECLINED", declinedAt: "2026-09-21T00:00:00" }),
      NOW,
    );
    expect(steps.at(-1)).toMatchObject({ label: "You declined the offer", state: "done" });
  });
});

describe("offer summary", () => {
  it("names a card's limit and rate", () => {
    expect(offerSummary(offer())).toBe("a $3,000.00 credit limit at 21.99% APR");
  });

  it("names a loan's amount, term and rate", () => {
    expect(offerSummary(offer({ creditLimit: null, approvedAmount: 4000, termMonths: 24, apr: 12.5 }))).toBe(
      "$4,000.00 over 24 months at 12.50% APR",
    );
  });
});
