import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { OfferActions } from "./OfferActions";

vi.mock("@/features/credit/actions", () => ({ acceptOfferAction: vi.fn(), declineOfferAction: vi.fn() }));

function inputsOf(container: HTMLElement) {
  return [...container.querySelectorAll("input")].map((input) => [input.name, input.type, input.value]);
}

describe("offer actions", () => {
  it("sends only the application id, with no field for terms", () => {
    const { container } = render(<OfferActions applicationId={42} />);
    fireEvent.click(screen.getByRole("button", { name: "Accept offer" }));
    expect(inputsOf(container)).toEqual([["applicationId", "hidden", "42"]]);
  });

  it("needs a second, explicit click to accept, naming the terms, and can be backed out of", () => {
    render(<OfferActions applicationId={42} terms="$4,000.00 over 24 months at 12.50% APR" />);
    const accept = screen.getByRole("button", { name: "Accept offer" });
    expect(accept).toHaveAttribute("type", "button");

    fireEvent.click(accept);
    expect(screen.getByText(/Accept \$4,000\.00 over 24 months at 12\.50% APR\?/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm acceptance" })).toHaveAttribute("type", "submit");
    expect(screen.queryByRole("button", { name: "Decline" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Back" }));
    expect(screen.queryByRole("button", { name: "Confirm acceptance" })).not.toBeInTheDocument();
  });

  it("needs a second, explicit click to decline, and can be backed out of", () => {
    render(<OfferActions applicationId={42} />);
    const decline = screen.getByRole("button", { name: "Decline" });
    expect(decline).toHaveAttribute("type", "button");

    fireEvent.click(decline);
    expect(screen.getByRole("button", { name: "Confirm decline" })).toHaveAttribute("type", "submit");

    fireEvent.click(screen.getByRole("button", { name: "Keep offer" }));
    expect(screen.queryByRole("button", { name: "Confirm decline" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Accept offer" })).toBeInTheDocument();
  });

  it("has nothing to submit until an answer is chosen", () => {
    const { container } = render(<OfferActions applicationId={42} />);
    expect(container.querySelectorAll("form")).toHaveLength(0);
  });
});
