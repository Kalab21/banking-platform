import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { OfferActions } from "./OfferActions";

vi.mock("@/features/credit/actions", () => ({ acceptOfferAction: vi.fn(), declineOfferAction: vi.fn() }));

describe("offer actions", () => {
  it("sends only the application id, with no field for terms", () => {
    const { container } = render(<OfferActions applicationId={42} />);
    const inputs = [...container.querySelectorAll("input")];
    expect(inputs.map((input) => [input.name, input.type, input.value])).toEqual([
      ["applicationId", "hidden", "42"],
    ]);
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
});
