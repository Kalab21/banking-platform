import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AddBeneficiary } from "@/features/payments/AddBeneficiary";
import type { BeneficiaryFormState } from "@/features/payments/actions";

/**
 * Saving a payee.
 *
 * The properties under test are the privacy ones. The account number is typed
 * into this form and must not outlive it: not in browser storage, not echoed
 * back unmasked, not left in the fields once the payee is saved. And the form
 * never offers a way to say whose profile it is writing to — that comes from
 * the session, server-side.
 */

const submissions: FormData[] = [];
let nextState: BeneficiaryFormState = { status: "idle" };

vi.mock("@/features/payments/actions", () => ({
  BENEFICIARY_IDLE: { status: "idle" } as BeneficiaryFormState,
  addBeneficiaryAction: async (
    _prev: BeneficiaryFormState,
    formData: FormData,
  ): Promise<BeneficiaryFormState> => {
    submissions.push(formData);
    return nextState;
  },
}));

beforeEach(() => {
  submissions.length = 0;
  nextState = { status: "idle" };
  localStorage.clear();
  sessionStorage.clear();
});

async function fill(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: /add payee/i }));
  await user.type(screen.getByLabelText(/payee name/i), "Northwind Properties");
  await user.type(screen.getByLabelText(/account number/i), "BA260918777001");
  await user.selectOptions(screen.getByLabelText(/how they are paid/i), "EXTERNAL_ACH");
}

describe("the form", () => {
  it("stays closed until asked for, and reports its state", async () => {
    const user = userEvent.setup();
    render(<AddBeneficiary />);

    const toggle = screen.getByRole("button", { name: /add payee/i });
    expect(toggle).toHaveAttribute("aria-expanded", "false");

    await user.click(toggle);
    expect(screen.getByRole("button", { name: /close/i })).toHaveAttribute("aria-expanded", "true");
  });

  it("never offers a field naming whose profile the payee belongs to", async () => {
    const user = userEvent.setup();
    render(<AddBeneficiary />);
    await user.click(screen.getByRole("button", { name: /add payee/i }));

    // The session decides this on the server. A userId input here would be an
    // invitation to save a payee against someone else.
    expect(document.querySelector('[name="userId"]')).toBeNull();
  });

  it("does not invite the browser to remember an account number", async () => {
    const user = userEvent.setup();
    render(<AddBeneficiary />);
    await user.click(screen.getByRole("button", { name: /add payee/i }));

    expect(screen.getByLabelText(/account number/i)).toHaveAttribute("autocomplete", "off");
    expect(screen.getByLabelText(/routing number/i)).toHaveAttribute("autocomplete", "off");
  });

  it("sends what was typed, and no identity of its own", async () => {
    const user = userEvent.setup();
    render(<AddBeneficiary />);
    await fill(user);
    await user.click(screen.getByRole("button", { name: /save payee/i }));

    await waitFor(() => expect(submissions).toHaveLength(1));
    const sent = submissions[0];
    expect(sent.get("name")).toBe("Northwind Properties");
    expect(sent.get("accountNumber")).toBe("BA260918777001");
    expect(sent.get("beneficiaryType")).toBe("EXTERNAL_ACH");
    expect(sent.get("userId")).toBeNull();
  });
});

describe("after a payee is saved", () => {
  it("confirms with a masked number, never the one that was typed", async () => {
    const user = userEvent.setup();
    nextState = { status: "saved", name: "Northwind Properties", maskedNumber: "••••7001" };

    render(<AddBeneficiary />);
    await fill(user);
    await user.click(screen.getByRole("button", { name: /save payee/i }));

    const note = await screen.findByText(/northwind properties saved/i);
    expect(note).toHaveTextContent("••••7001");
    expect(document.body.textContent).not.toContain("BA260918777001");
  });

  it("clears the account number out of the form", async () => {
    const user = userEvent.setup();
    nextState = { status: "saved", name: "Northwind Properties", maskedNumber: "••••7001" };

    render(<AddBeneficiary />);
    await fill(user);
    await user.click(screen.getByRole("button", { name: /save payee/i }));
    await screen.findByText(/northwind properties saved/i);

    await waitFor(() => expect(screen.getByLabelText(/account number/i)).toHaveValue(""));
    expect(screen.getByLabelText(/payee name/i)).toHaveValue("");
  });

  it("leaves nothing in browser storage", async () => {
    const user = userEvent.setup();
    nextState = { status: "saved", name: "Northwind Properties", maskedNumber: "••••7001" };

    render(<AddBeneficiary />);
    await fill(user);
    await user.click(screen.getByRole("button", { name: /save payee/i }));
    await screen.findByText(/northwind properties saved/i);

    expect(Object.keys(localStorage)).toHaveLength(0);
    expect(Object.keys(sessionStorage)).toHaveLength(0);
  });
});

describe("when it does not work", () => {
  it("shows the field messages the server sent back", async () => {
    const user = userEvent.setup();
    nextState = { status: "invalid", fields: { accountNumber: "Enter the account number" } };

    render(<AddBeneficiary />);
    await fill(user);
    await user.click(screen.getByRole("button", { name: /save payee/i }));

    expect(await screen.findByText("Enter the account number")).toBeInTheDocument();
    expect(screen.getByLabelText(/account number/i)).toHaveAttribute("aria-invalid", "true");
  });

  it("announces a refusal rather than only showing it", async () => {
    const user = userEvent.setup();
    nextState = { status: "failed", error: "That payee could not be saved." };

    render(<AddBeneficiary />);
    await fill(user);
    await user.click(screen.getByRole("button", { name: /save payee/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent("That payee could not be saved.");
  });
});
