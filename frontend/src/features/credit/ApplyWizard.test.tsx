import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ApplyWizard } from "./ApplyWizard";
import { creditProduct } from "./products";
import type { CreditFormState } from "./actions";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const sent: FormData[] = [];
let reply: CreditFormState = {};
vi.mock("@/features/credit/actions", () => ({
  applyForCreditAction: async (_prev: CreditFormState, formData: FormData) => {
    sent.push(formData);
    return reply;
  },
}));

beforeEach(() => {
  sent.length = 0;
  reply = {};
  push.mockReset();
});

const loan = creditProduct("PERSONAL_LOAN")!;
const card = creditProduct("CREDIT_CARD")!;

describe("applying in steps", () => {
  it("catches a missing answer on its own step and sends nothing", async () => {
    const user = userEvent.setup();
    render(<ApplyWizard product={loan} />);
    await user.click(screen.getByRole("button", { name: /Continue/ }));
    expect(screen.getByText("Enter how much you would like to borrow")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("Step 1 of 3");
    expect(sent).toHaveLength(0);
  });

  it("sends exactly what was reviewed, then opens the application's page", async () => {
    const user = userEvent.setup();
    reply = { success: "Application submitted.", applicationId: 77 };
    render(<ApplyWizard product={loan} />);

    await user.type(screen.getByLabelText("How much would you like to borrow?"), "4,000.00");
    await user.selectOptions(screen.getByLabelText("Over how long?"), "24");
    await user.type(screen.getByLabelText("What is it for?"), "Kitchen repair");
    await user.click(screen.getByRole("button", { name: /Continue/ }));

    expect(screen.getByRole("heading", { level: 2 })).toHaveFocus();
    await user.type(screen.getByLabelText("Your annual income before tax"), "90000");
    await user.type(screen.getByLabelText("What you already pay each month towards other debts"), "0");
    await user.click(screen.getByRole("button", { name: /Continue/ }));

    const review = screen.getByTestId("application-review");
    expect(review).toHaveTextContent("$4,000.00");
    expect(review).toHaveTextContent("24 months");
    await user.click(screen.getByRole("button", { name: "Submit application" }));

    await waitFor(() => expect(sent).toHaveLength(1));
    const form = sent[0];
    expect(form.get("applicationType")).toBe("PERSONAL_LOAN");
    expect(form.get("requestedAmount")).toBe("4000.00");
    expect(form.get("termMonths")).toBe("24");
    expect(form.get("monthlyDebtObligations")).toBe("0");
    // Nothing the bank decides travels with the application.
    for (const name of ["creditScore", "apr", "creditLimit", "cardTier", "interestRate"]) {
      expect(form.has(name)).toBe(false);
    }
    await waitFor(() => expect(push).toHaveBeenCalledWith("/applications/77"));
  });

  it("goes back to the step an answer came from to change it, keeping the rest", async () => {
    const user = userEvent.setup();
    render(<ApplyWizard product={card} />);
    await user.click(screen.getByRole("button", { name: /Continue/ }));
    await user.type(screen.getByLabelText("Your annual income before tax"), "72000");
    await user.type(screen.getByLabelText("What you already pay each month towards other debts"), "300");
    await user.click(screen.getByRole("button", { name: /Continue/ }));

    await user.click(screen.getByRole("button", { name: "Change annual income" }));
    expect(screen.getByLabelText("Your annual income before tax")).toHaveValue("72000");
    expect(screen.getByLabelText("What you already pay each month towards other debts")).toHaveValue("300");
  });

  it("offers a card applicant no limit, rate or amount to choose", () => {
    render(<ApplyWizard product={card} />);
    expect(screen.queryByLabelText("How much would you like to borrow?")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Over how long?")).not.toBeInTheDocument();
    expect(screen.getByText(/You do not choose a limit or a rate/)).toBeInTheDocument();
  });

  it("shows a refusal from the backend on the review step", async () => {
    const user = userEvent.setup();
    reply = { error: "You already have an application in progress." };
    render(<ApplyWizard product={card} />);
    await user.click(screen.getByRole("button", { name: /Continue/ }));
    await user.type(screen.getByLabelText("Your annual income before tax"), "72000");
    await user.type(screen.getByLabelText("What you already pay each month towards other debts"), "0");
    await user.click(screen.getByRole("button", { name: /Continue/ }));
    await user.click(screen.getByRole("button", { name: "Submit application" }));
    expect(await screen.findByText("You already have an application in progress.")).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });
});
