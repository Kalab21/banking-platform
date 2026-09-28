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
    const props = { applicationId: 7, requestedAmount: 6000, currency: "USD" };
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

  it("offers nothing to decide once the application is decided", () => {
    render(<ReviewDecisionForm applicationId={7} requestedAmount={6000} currency="USD" open={false} />);
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.getByText(/has been decided/)).toBeInTheDocument();
  });
});
