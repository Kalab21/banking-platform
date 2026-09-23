import { describe, expect, it } from "vitest";
import { awaitingCustomer, reasonText, statusSummary } from "@/features/credit/reasons";
import type { Application, ApplicationStatus, ReasonCode } from "@/types/api";

function application(status: ApplicationStatus): Application {
  return {
    id: 1,
    userId: 7,
    applicationType: "PERSONAL_LOAN",
    status,
    requestedAmount: 10000,
    approvedAmount: null,
    currency: "USD",
    termMonths: 36,
    purpose: null,
    creditScoreAtApply: null,
    reviewerNotes: null,
    productId: null,
    appliedAt: null,
    reviewedAt: null,
    createdAt: "2026-09-22T00:00:00Z",
  };
}

describe("what a customer is told", () => {
  const codes: ReasonCode[] = [
    "CREDIT_SCORE_BELOW_MINIMUM",
    "DTI_ABOVE_POLICY",
    "LTV_ABOVE_POLICY",
    "REQUEST_AMOUNT_ABOVE_POLICY",
    "TERM_NOT_SUPPORTED",
    "KYC_REVIEW_REQUIRED",
    "KYC_REJECTED",
    "MANUAL_REVIEW_REQUIRED",
    "INSUFFICIENT_INFORMATION",
  ];

  it("has wording for every reason the backend can give", () => {
    // A code with no wording would fall through to a generic line and quietly
    // stop explaining anything.
    for (const code of codes) {
      expect(reasonText(code)).not.toBe("");
    }
  });

  it("never shows the code itself", () => {
    // The codes are for counting and filtering. A customer reading
    // "DTI_ABOVE_POLICY" has been shown the bank's filing system.
    for (const code of codes) {
      expect(reasonText(code)).not.toContain(code);
      expect(reasonText(code)).not.toMatch(/[A-Z]{3,}_[A-Z]/);
    }
  });

  it("quotes no threshold a customer could aim at", () => {
    // Publishing "you needed 640" invites gaming the policy rather than
    // understanding it, and the number would go stale the moment it changed.
    for (const code of codes) {
      expect(reasonText(code)).not.toMatch(/\d/);
    }
  });
});

describe("where an application has got to", () => {
  it("does not claim a product exists while it is still provisioning", () => {
    // PROVISIONING means asked for and not confirmed. Saying the card is ready
    // here is the mistake the whole lifecycle was rebuilt to stop.
    const summary = statusSummary(application("PROVISIONING"));
    expect(summary).not.toMatch(/ready/i);
    expect(summary).toMatch(/setting/i);
  });

  it("says a product is ready only once it is provisioned", () => {
    expect(statusSummary(application("PROVISIONED"))).toMatch(/ready/i);
  });

  it("describes every status the backend can return", () => {
    const statuses: ApplicationStatus[] = [
      "SUBMITTED",
      "UNDER_REVIEW",
      "MANUAL_REVIEW",
      "OFFERED",
      "ACCEPTED",
      "DECLINED",
      "REJECTED",
      "PROVISIONING",
      "PROVISIONED",
      "CANCELLED",
    ];
    for (const status of statuses) {
      expect(statusSummary(application(status))).not.toBe("");
    }
  });
});

describe("whether the customer has something to do", () => {
  it("is waiting on them only when an offer is open", () => {
    expect(awaitingCustomer(application("OFFERED"))).toBe(true);
  });

  it("is not waiting on them once they have answered", () => {
    for (const status of ["ACCEPTED", "DECLINED", "PROVISIONING", "PROVISIONED"] as const) {
      expect(awaitingCustomer(application(status))).toBe(false);
    }
  });

  it("is not waiting on them while the bank is still deciding", () => {
    for (const status of ["SUBMITTED", "UNDER_REVIEW", "MANUAL_REVIEW"] as const) {
      expect(awaitingCustomer(application(status))).toBe(false);
    }
  });
});
