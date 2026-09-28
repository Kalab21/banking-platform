import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { reviewKycAction } from "@/features/kyc/actions";
import { KycReviewList } from "./KycReviewPanel";
import { KycStatusControl } from "./KycStatusControl";
import type { KycDocument } from "@/types/api";

vi.mock("@/features/kyc/actions", () => ({ reviewKycAction: vi.fn(), setKycStatusAction: vi.fn() }));

const doc = (status: KycDocument["status"]): KycDocument => ({
  id: 5, userId: 10, documentType: "PASSPORT", documentRef: "DEMO-PASSPORT-0001", status,
  rejectionReason: null, reviewedBy: null, reviewedAt: null, createdAt: "2026-09-27T10:00:00",
});

describe("staff document review", () => {
  it.each(["SUBMITTED", "UNDER_REVIEW"] as const)("offers a decision on a %s document", (status) => {
    // The panel compared against PENDING, which documents never are, so the
    // buttons never appeared.
    render(<KycReviewList documents={[doc(status)]} />);
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
  });

  it("sends the decision with the button, and keeps the confirmation once the document is decided", async () => {
    const sent: FormData[] = [];
    vi.mocked(reviewKycAction).mockImplementation(async (_prev, formData: FormData) => {
      sent.push(formData);
      return { success: "Document approved." };
    });
    const user = userEvent.setup();
    const { rerender } = render(<KycReviewList documents={[doc("SUBMITTED")]} />);
    await user.click(screen.getByRole("button", { name: "Approve" }));
    expect(await screen.findByText("Document approved.")).toBeInTheDocument();
    expect(sent[0].get("decision")).toBe("APPROVED");

    // The page refreshes into the decided state; the confirmation stays.
    rerender(<KycReviewList documents={[doc("APPROVED")]} />);
    expect(screen.getByText("Document approved.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it.each(["APPROVED", "REJECTED"] as const)("offers nothing on a %s document", (status) => {
    render(<KycReviewList documents={[doc(status)]} />);
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });
});

describe("customer identity decision", () => {
  it("offers approve and reject while the check is unfinished", () => {
    render(<KycStatusControl userId={10} status="IN_REVIEW" isSelf={false} />);
    expect(screen.getByRole("button", { name: "Approve identity" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject identity" })).toBeInTheDocument();
  });

  it("offers nothing to a member of staff looking at themselves", () => {
    render(<KycStatusControl userId={99} status="IN_REVIEW" isSelf />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("offers nothing once decided", () => {
    render(<KycStatusControl userId={10} status="APPROVED" isSelf={false} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
