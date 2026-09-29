import { beforeEach, describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ServicingPayment, type ServicingPaymentProps } from "@/features/servicing/ServicingPayment";
import type { MoneyFormState } from "@/features/money/state";
import type { MoneyAccountOption } from "@/features/transactions/money-account";

const ACCOUNTS: MoneyAccountOption[] = [
  { id: 68, label: "Checking ••••2024 — $1,131.99", maskedNumber: "••••2024", currency: "USD", availableBalance: 1131.99 },
  { id: 69, label: "Savings ••••9716 — $714.18", maskedNumber: "••••9716", currency: "USD", availableBalance: 714.18 },
];

const submissions: FormData[] = [];
let replies: MoneyFormState[] = [];

async function action(_prev: MoneyFormState, formData: FormData): Promise<MoneyFormState> {
  submissions.push(formData);
  return replies.shift() ?? { status: "idle" };
}

function props(over: Partial<ServicingPaymentProps> = {}): ServicingPaymentProps {
  return {
    testId: "card-pay",
    title: "Pay your card",
    description: "Pay towards your balance.",
    action,
    target: { name: "cardId", id: 9 },
    currency: "USD",
    accounts: ACCOUNTS,
    choices: [
      { id: "minimum", label: "Minimum payment", amount: 25 },
      { id: "current", label: "Current balance", amount: 640 },
    ],
    allowOther: true,
    confirmLabel: "Confirm payment",
    available: true,
    ...over,
  };
}

beforeEach(() => {
  submissions.length = 0;
  replies = [];
});

async function reviewAndConfirm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: /Review/ }));
  await user.click(screen.getByRole("button", { name: "Confirm payment" }));
  await waitFor(() => expect(submissions.length).toBeGreaterThan(0));
}

// Driven through userEvent, several steps a test; the 5 s default was exceeded
// on a loaded host with the stack running, once, in the first test to render.
describe("paying a loan or card", { timeout: 15_000 }, () => {
  it("sends the chosen amount from the chosen account, for this card", async () => {
    const user = userEvent.setup();
    render(<ServicingPayment {...props()} />);
    await user.click(screen.getByRole("radio", { name: /Current balance/ }));
    await user.selectOptions(screen.getByLabelText("Pay from"), "69");
    await reviewAndConfirm(user);

    const sent = submissions[0];
    expect(sent.get("amount")).toBe("640.00");
    expect(sent.get("sourceAccountId")).toBe("69");
    expect(sent.get("cardId")).toBe("9");
    expect(String(sent.get("operationId"))).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("sends another amount the customer types", async () => {
    const user = userEvent.setup();
    render(<ServicingPayment {...props()} />);
    await user.click(screen.getByRole("radio", { name: "Other amount" }));
    await user.type(screen.getByLabelText("Other amount", { selector: "input:not([type=radio])" }), "77.10");
    await user.selectOptions(screen.getByLabelText("Pay from"), "68");
    expect(screen.getByRole("button", { name: /Review/ })).toBeEnabled();
    await reviewAndConfirm(user);
    expect(submissions[0].get("amount")).toBe("77.10");
  });

  it("retries a refused payment as the same operation", async () => {
    const user = userEvent.setup();
    replies = [{ status: "settled", outcome: { kind: "rejected", message: "Insufficient funds." } }];
    render(<ServicingPayment {...props()} />);
    await user.selectOptions(screen.getByLabelText("Pay from"), "68");
    await reviewAndConfirm(user);
    expect(await screen.findByText("Insufficient funds.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Confirm payment" }));
    await waitFor(() => expect(submissions).toHaveLength(2));
    expect(submissions[1].get("operationId")).toBe(submissions[0].get("operationId"));
  });

  it("shows what the backend took, and keeps the receipt when the page stops offering payment", async () => {
    const user = userEvent.setup();
    replies = [{ status: "settled", outcome: { kind: "succeeded", reference: "CT-7", message: "Payment made.", amount: 42.5 } }];
    const { rerender } = render(<ServicingPayment {...props()} />);
    await user.click(screen.getByRole("radio", { name: /Current balance/ }));
    await user.selectOptions(screen.getByLabelText("Pay from"), "68");
    await reviewAndConfirm(user);

    expect(await screen.findByRole("status")).toHaveTextContent("Payment made.");
    expect(screen.getByText("$42.50")).toBeInTheDocument();
    expect(screen.getByTestId("card-pay-reference")).toHaveTextContent("CT-7");

    // The page refreshes into "nothing owed"; the receipt must not vanish.
    rerender(<ServicingPayment {...props({ available: false, choices: [], unavailable: "Nothing to pay." })} />);
    expect(screen.getByRole("status")).toHaveTextContent("Payment made.");
    expect(screen.queryByRole("button", { name: "Make another payment" })).not.toBeInTheDocument();
  });

  it("offers no way to resend a payment whose outcome is unknown", async () => {
    const user = userEvent.setup();
    replies = [{ status: "settled", outcome: { kind: "unknown", message: "We could not confirm it." } }];
    render(<ServicingPayment {...props()} />);
    await user.selectOptions(screen.getByLabelText("Pay from"), "68");
    await reviewAndConfirm(user);
    expect(await screen.findByRole("alert")).toHaveTextContent("We could not confirm this payment");
    expect(screen.queryByRole("button", { name: "Confirm payment" })).not.toBeInTheDocument();
  });

  it("sends no key for receiving a loan's money", async () => {
    const user = userEvent.setup();
    render(
      <ServicingPayment
        {...props({
          testId: "receive-funds",
          target: { name: "loanId", id: 4 },
          choices: [{ id: "principal", label: "Loan amount", amount: 4000 }],
          allowOther: false,
          accountLabel: "Receive into",
          confirmLabel: "Confirm payment",
          keyed: false,
        })}
      />,
    );
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Receive into"), "68");
    await reviewAndConfirm(user);
    expect(submissions[0].get("loanId")).toBe("4");
    expect(submissions[0].has("operationId")).toBe(false);
  });

  it("says why when it cannot be used, and nothing when it has nothing to say", () => {
    const { rerender, container } = render(
      <ServicingPayment {...props({ available: false, unavailable: "Nothing to pay." })} />,
    );
    expect(screen.getByText("Nothing to pay.")).toBeInTheDocument();
    rerender(<ServicingPayment {...props({ available: false })} />);
    expect(container).toBeEmptyDOMElement();
  });
});
