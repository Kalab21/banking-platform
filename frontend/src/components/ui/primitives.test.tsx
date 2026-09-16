import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Badge, EmptyState, ErrorState, StatTile, statusTone } from "@/components/ui/primitives";

describe("EmptyState", () => {
  it("tells the user what is missing and why", () => {
    render(
      <EmptyState
        title="No transactions yet"
        description="Deposits and transfers will appear here."
      />,
    );

    expect(screen.getByText("No transactions yet")).toBeInTheDocument();
    expect(screen.getByText("Deposits and transfers will appear here.")).toBeInTheDocument();
  });
});

describe("ErrorState", () => {
  it("announces itself to assistive technology", () => {
    render(<ErrorState message="The gateway is unreachable." />);

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("The gateway is unreachable.");
  });

  it("uses a caller-supplied title when given one", () => {
    render(<ErrorState title="Could not load accounts" message="Try again shortly." />);
    expect(screen.getByText("Could not load accounts")).toBeInTheDocument();
  });
});

describe("StatTile", () => {
  it("shows the label, value and hint", () => {
    render(<StatTile label="Total balance" value="$1,234.50" hint="2 accounts" />);

    expect(screen.getByText("Total balance")).toBeInTheDocument();
    expect(screen.getByText("$1,234.50")).toBeInTheDocument();
    expect(screen.getByText("2 accounts")).toBeInTheDocument();
  });
});

describe("Badge", () => {
  it("renders its content", () => {
    render(<Badge tone="positive">Active</Badge>);
    expect(screen.getByText("Active")).toBeInTheDocument();
  });
});

describe("statusTone", () => {
  it("maps healthy states to the positive tone", () => {
    expect(statusTone("ACTIVE")).toBe("positive");
    expect(statusTone("VERIFIED")).toBe("positive");
    expect(statusTone("PAID_OFF")).toBe("positive");
  });

  it("maps states needing attention to the caution tone", () => {
    expect(statusTone("PENDING")).toBe("caution");
    expect(statusTone("IN_REVIEW")).toBe("caution");
  });

  it("maps blocking and failure states to the critical tone", () => {
    expect(statusTone("FROZEN")).toBe("critical");
    expect(statusTone("REJECTED")).toBe("critical");
    expect(statusTone("OVERDRAWN")).toBe("critical");
  });

  it("falls back to neutral for anything unrecognised", () => {
    expect(statusTone("SOMETHING_NEW")).toBe("neutral");
    expect(statusTone(null)).toBe("neutral");
  });
});
