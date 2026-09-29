import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReviewDecisionForm } from "./ReviewDecisionForm";
import { reviewApplicationAction } from "@/features/admin/review-actions";

vi.mock("@/features/admin/review-actions", () => ({ reviewApplicationAction: vi.fn() }));

describe("a reviewer's decision", () => {
  it("keeps the confirmation when the page refreshes into the decided state", async () => {
    const sent: FormData[] = [];
    vi.mocked(reviewApplicationAction).mockImplementation(async (_prev, formData: FormData) => {
      sent.push(formData);
      return { success: "Rejected. No offer will be made." };
    });
    const user = userEvent.setup();
    const props = { applicationId: 7, requestedAmount: 6000, currency: "USD", identityApproved: true };
    const { rerender } = render(<ReviewDecisionForm {...props} open />);

    await user.click(screen.getByRole("button", { name: "Reject" }));
    await user.type(screen.getByLabelText(/Reason for rejection/), "Could not verify identity");
    await user.click(screen.getByRole("button", { name: "Confirm rejection" }));
    expect(await screen.findByText(/No offer will be made/)).toBeInTheDocument();
    expect(sent[0].get("decision")).toBe("REJECT");

    // The application is no longer referred; the confirmation stays.
    rerender(<ReviewDecisionForm {...props} open={false} />);
    expect(screen.getByText(/No offer will be made/)).toBeInTheDocument();
  });

  it("choosing Approve asks for the amount and notes, and sends nothing yet", async () => {
    const sent: FormData[] = [];
    vi.mocked(reviewApplicationAction).mockImplementation(async (_prev, formData: FormData) => {
      sent.push(formData);
      return {};
    });
    const user = userEvent.setup();
    render(<ReviewDecisionForm applicationId={7} requestedAmount={6000} currency="USD" open identityApproved />);
    await user.click(screen.getByRole("button", { name: "Approve" }));
    // The clicked button used to be reused as the submit button, and the
    // browser submitted the approval before anything was entered.
    expect(screen.getByLabelText("Amount to approve")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm approval" })).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(sent).toHaveLength(0);
  });

  it("will not approve credit until the customer's identity is approved, but can still reject", () => {
    render(<ReviewDecisionForm applicationId={7} requestedAmount={6000} currency="USD" open identityApproved={false} />);
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reject" })).toBeEnabled();
    expect(screen.getByText(/Approve the customer.s identity before approving credit/)).toBeInTheDocument();
  });

  it("offers nothing to decide once the application is decided", () => {
    render(<ReviewDecisionForm applicationId={7} requestedAmount={6000} currency="USD" open={false} identityApproved />);
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.getByText(/has been decided/)).toBeInTheDocument();
  });
});
