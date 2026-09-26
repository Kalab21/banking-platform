import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { FreezeControl } from "./FreezeControl";

vi.mock("@/features/cards/actions", () => ({ setCardFreezeAction: vi.fn() }));

describe("customer card controls", () => {
  it.each([
    ["ACTIVE", "Freeze card", "freeze"],
    ["CUSTOMER_FROZEN", "Unfreeze card", "unfreeze"],
  ])("offers the permitted move for %s", (status, label, intent) => {
    render(<FreezeControl cardId={42} status={status} />);
    const button = screen.getByRole("button", { name: label });
    const form = new FormData(button.closest("form")!);
    expect(form.get("cardId")).toBe("42");
    expect(form.get("intent")).toBe(intent);
    expect(screen.getAllByRole("button")).toHaveLength(1);
  });

  it.each(["SYSTEM_BLOCKED", "DEFAULTED", "CLOSED"])("shows information only for %s", (status) => {
    render(<FreezeControl cardId={42} status={status} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText(/Please contact us/)).toBeInTheDocument();
  });
});
