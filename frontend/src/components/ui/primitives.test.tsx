import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  Badge,
  EmptyState,
  ErrorState,
  Money,
  ProgressBar,
  StatTile,
  statusTone,
} from "@/components/ui/primitives";

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

describe("Money", () => {
  it("renders an amount in its own currency, not a hardcoded one", () => {
    render(<Money amount={1234.5} currency="EUR" />);
    expect(screen.getByText("€1,234.50")).toBeInTheDocument();
  });

  it("shows a dash rather than NaN, Infinity or null", () => {
    const { rerender } = render(<Money amount={Number.NaN} />);
    expect(screen.getByText("—")).toBeInTheDocument();

    rerender(<Money amount={null} />);
    expect(screen.getByText("—")).toBeInTheDocument();

    rerender(<Money amount={undefined} />);
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("signs a figure when asked, taking the sign from the amount", () => {
    const { rerender } = render(<Money amount={250} signed />);
    expect(screen.getByText("+$250.00")).toBeInTheDocument();

    rerender(<Money amount={-250} signed />);
    expect(screen.getByText("−$250.00")).toBeInTheDocument();
  });

  it("keeps the sign of a negative balance when not asked to sign it", () => {
    // An overdraft is real. Dropping the minus would show money that is not there.
    render(<Money amount={-240.5} />);
    expect(screen.getByText("-$240.50")).toBeInTheDocument();
  });
});

describe("ProgressBar", () => {
  it("carries a name that says what it measures", () => {
    render(<ProgressBar value={25} label="25% of a $10,000 credit limit used" />);

    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveAccessibleName("25% of a $10,000 credit limit used");
    expect(bar).toHaveAttribute("aria-valuenow", "25");
  });

  it("clamps what it draws without being asked to lie about it", () => {
    // The caller reports the true figure in text; the bar only has to stay
    // inside its track.
    const { rerender } = render(<ProgressBar value={140} label="Over limit" />);
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "100");

    rerender(<ProgressBar value={-20} label="Below zero" />);
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "0");
  });

  it("survives a value that is not a number", () => {
    render(<ProgressBar value={Number.NaN} label="Unknown" />);
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "0");
  });
});
