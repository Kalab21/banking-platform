import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MoveMoney } from "@/features/money/MoveMoney";
import type { MoneyFormState } from "@/features/money/actions";
import type { MoneyAccountOption } from "@/features/transactions/money-account";

/**
 * The money-movement flow, exercised the way a customer drives it.
 *
 * Two properties matter more than the markup: one customer operation must send
 * one idempotency key no matter how many times it is attempted, and a request
 * whose outcome is unknown must not offer any way to send it again. Both are
 * asserted here rather than left to a live test, because both are about what
 * the browser does and neither needs a backend to demonstrate.
 */

const ACCOUNTS: MoneyAccountOption[] = [
  {
    id: 68,
    label: "Checking ••••2024 — $11,131.99",
    maskedNumber: "••••2024",
    currency: "USD",
    availableBalance: 11_631.99,
  },
  {
    id: 69,
    label: "Savings ••••9716 — $16,714.18",
    maskedNumber: "••••9716",
    currency: "USD",
    availableBalance: 16_714.18,
  },
];

/** Captures what each submission sent, and decides what comes back. */
const submissions: FormData[] = [];
let nextState: MoneyFormState = { status: "idle" };
/** When set, the action waits here so the pending state can be observed. */
let hang = false;
let release: (() => void) | null = null;

/*
 * The real module is `"use server"` and reaches `server-only` through the API
 * client, so it cannot be imported into a jsdom render at all. It is replaced
 * wholesale rather than partially: the component only needs the three actions
 * and the idle state.
 */
vi.mock("@/features/money/actions", () => {
  const action = async (_prev: MoneyFormState, formData: FormData): Promise<MoneyFormState> => {
    submissions.push(formData);
    if (hang) await new Promise<void>((resolve) => (release = resolve));
    return nextState;
  };
  return {
    IDLE: { status: "idle" } as MoneyFormState,
    depositAction: action,
    withdrawAction: action,
    transferAction: action,
  };
});

beforeEach(() => {
  submissions.length = 0;
  nextState = { status: "idle" };
  hang = false;
});

afterEach(() => {
  release?.();
  release = null;
});

async function fillTransfer(user: ReturnType<typeof userEvent.setup>, amount = "25.00") {
  await user.selectOptions(screen.getByLabelText("From"), "68");
  await user.selectOptions(screen.getByLabelText("To"), "69");
  await user.type(screen.getByLabelText("Amount"), amount);
  await user.click(screen.getByRole("button", { name: /review transfer/i }));
}

describe("choosing what to do", () => {
  it("offers one action at a time rather than three armed forms", () => {
    render(<MoveMoney accounts={ACCOUNTS} />);

    expect(screen.getAllByRole("tab")).toHaveLength(3);
    expect(screen.getByRole("tab", { name: "Transfer" })).toHaveAttribute("aria-selected", "true");
    // Only the selected flow is on screen.
    expect(screen.getAllByRole("button", { name: /^review/i })).toHaveLength(1);
  });

  it("tells a customer with no accounts why nothing is available", () => {
    render(<MoveMoney accounts={[]} />);

    expect(screen.getByText("You need an account first")).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  });
});

describe("the review step", () => {
  it("shows the amount and masked accounts, never a full number", async () => {
    const user = userEvent.setup();
    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);

    const review = screen.getByTestId("review-panel");
    expect(review).toHaveTextContent("$25.00");
    expect(review).toHaveTextContent("••••2024");
    expect(review).toHaveTextContent("••••9716");
  });

  it("shows the available balance before a withdrawal is confirmed", async () => {
    const user = userEvent.setup();
    render(<MoveMoney accounts={ACCOUNTS} />);

    await user.click(screen.getByRole("tab", { name: "Withdraw" }));
    await user.selectOptions(screen.getByLabelText("Account"), "68");
    await user.type(screen.getByLabelText("Amount"), "40");
    await user.click(screen.getByRole("button", { name: /review withdraw/i }));

    expect(screen.getByTestId("review-panel")).toHaveTextContent("$11,631.99");
  });

  it("will not open a review for a transfer to the same account", async () => {
    const user = userEvent.setup();
    render(<MoveMoney accounts={ACCOUNTS} />);

    await user.selectOptions(screen.getByLabelText("From"), "68");
    await user.selectOptions(screen.getByLabelText("To"), "68");
    await user.type(screen.getByLabelText("Amount"), "25.00");

    expect(screen.getByRole("button", { name: /review transfer/i })).toBeDisabled();
  });
});

describe("one operation, one idempotency key", () => {
  it("sends an opaque operation id with the confirmed request", async () => {
    const user = userEvent.setup();
    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);
    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));

    await waitFor(() => expect(submissions).toHaveLength(1));
    const id = submissions[0].get("operationId");
    // Opaque: a UUID, not a value derived from the amount or the accounts.
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  });

  it("reuses the same key when a refused request is attempted again", async () => {
    // A refusal that moved no money may be retried — and that retry is the
    // same logical operation, so it must not mint a new key.
    const user = userEvent.setup();
    nextState = { status: "settled", outcome: { kind: "rejected", message: "Insufficient funds." } };

    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);
    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));
    await screen.findByText("Insufficient funds.");

    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));
    await waitFor(() => expect(submissions).toHaveLength(2));

    expect(submissions[1].get("operationId")).toBe(submissions[0].get("operationId"));
  });

  it("mints a new key only when the customer starts a fresh payment", async () => {
    const user = userEvent.setup();
    nextState = {
      status: "settled",
      outcome: { kind: "succeeded", reference: "ref-1", message: "Transfer complete." },
    };

    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);
    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));
    await screen.findByTestId("receipt");

    await user.click(screen.getByRole("button", { name: /make another transfer/i }));
    await fillTransfer(user);
    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));
    await waitFor(() => expect(submissions).toHaveLength(2));

    expect(submissions[1].get("operationId")).not.toBe(submissions[0].get("operationId"));
  });
});

describe("double submission", () => {
  it("disables confirm while the request is in flight", async () => {
    const user = userEvent.setup();
    hang = true;

    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);

    const confirm = screen.getByRole("button", { name: /confirm transfer/i });
    await user.click(confirm);

    await waitFor(() => expect(submissions).toHaveLength(1));
    await waitFor(() => expect(screen.getByRole("button", { name: /sending/i })).toBeDisabled());

    // A second click while pending cannot start a second operation.
    await user.click(screen.getByRole("button", { name: /sending/i }));
    expect(submissions).toHaveLength(1);
  });
});

describe("an outcome nobody knows", () => {
  it("does not claim the payment failed or succeeded", async () => {
    const user = userEvent.setup();
    nextState = {
      status: "settled",
      outcome: {
        kind: "unknown",
        message:
          "We could not confirm whether this went through. Do not send it again yet — " +
          "check your transaction history first to see whether it was applied.",
      },
    };

    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);
    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));

    const panel = await screen.findByTestId("unresolved");
    expect(panel).toHaveTextContent("We could not confirm this payment");
    expect(panel).not.toHaveTextContent(/complete|failed/i);
  });

  it("offers no way to send the request again", async () => {
    const user = userEvent.setup();
    nextState = { status: "settled", outcome: { kind: "unknown", message: "Unconfirmed." } };

    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);
    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));
    await screen.findByTestId("unresolved");

    // The only route out is the history, which is the thing that answers it.
    expect(screen.queryByRole("button", { name: /confirm|retry|try again|send/i })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /view transactions/i })).toHaveAttribute(
      "href",
      "/transactions",
    );
  });

  it("announces itself, rather than only appearing", async () => {
    const user = userEvent.setup();
    nextState = { status: "settled", outcome: { kind: "unknown", message: "Unconfirmed." } };

    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);
    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent("We could not confirm this payment");
  });
});

describe("the receipt", () => {
  it("shows the backend's own reference and nothing invented", async () => {
    const user = userEvent.setup();
    nextState = {
      status: "settled",
      outcome: { kind: "succeeded", reference: "a1b2c3d4", message: "Transfer complete." },
    };

    render(<MoveMoney accounts={ACCOUNTS} />);
    await fillTransfer(user);
    await user.click(screen.getByRole("button", { name: /confirm transfer/i }));

    const receipt = await screen.findByTestId("receipt");
    expect(receipt).toHaveTextContent("Transfer complete.");
    expect(receipt).toHaveTextContent("$25.00");
    expect(receipt).toHaveTextContent("a1b2c3d4");
    expect(receipt).toHaveTextContent("••••2024");
    expect(receipt).toHaveTextContent("••••9716");
  });
});
