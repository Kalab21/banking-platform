import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Button, MoneyField, SelectField, TextField } from "@/components/ui/form";

describe("TextField", () => {
  it("binds its label to the input, so clicking the label focuses it", () => {
    render(<TextField label="Username" name="username" />);

    const input = screen.getByLabelText("Username");
    expect(input).toHaveAttribute("name", "username");
  });

  it("announces a validation error and links it to the input", () => {
    render(<TextField label="Username" name="username" error="Enter your username" />);

    const input = screen.getByLabelText("Username");
    expect(input).toHaveAttribute("aria-invalid", "true");

    // The described-by target must be the element actually carrying the message,
    // whatever id was generated for it.
    const describedBy = input.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy as string)).toHaveTextContent(
      "Enter your username",
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Enter your username");
  });

  it("is not marked invalid when there is no error", () => {
    render(<TextField label="Username" name="username" />);
    expect(screen.getByLabelText("Username")).not.toHaveAttribute("aria-invalid");
  });

  it("describes the input by its hint when one is given", () => {
    render(<TextField label="Phone" name="phone" hint="Optional" />);

    const input = screen.getByLabelText("Phone");
    const describedBy = input.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy as string)).toHaveTextContent("Optional");
  });

  it("gives each field a unique id, so repeated forms do not collide", () => {
    render(
      <>
        <TextField label="Deposit amount" name="amount" />
        <TextField label="Transfer amount" name="amount" />
      </>,
    );

    const first = screen.getByLabelText("Deposit amount");
    const second = screen.getByLabelText("Transfer amount");

    expect(first.id).not.toBe(second.id);
    expect(first).not.toBe(second);
  });
});

describe("MoneyField", () => {
  it("uses a decimal keypad on touch devices", () => {
    render(<MoneyField label="Amount" name="amount" />);
    expect(screen.getByLabelText("Amount")).toHaveAttribute("inputMode", "decimal");
  });

  it("reports its own validation error", () => {
    render(<MoneyField label="Amount" name="amount" error="Amount must be greater than zero" />);
    expect(screen.getByRole("alert")).toHaveTextContent("Amount must be greater than zero");
  });
});

describe("SelectField", () => {
  it("renders its options against a bound label", () => {
    render(
      <SelectField label="Account" name="accountId">
        <option value="">Select an account</option>
        <option value="1">Checking</option>
      </SelectField>,
    );

    expect(screen.getByLabelText("Account")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Checking" })).toBeInTheDocument();
  });
});

describe("Button", () => {
  it("disables itself and reports progress while pending, preventing a double submit", () => {
    render(<Button pending>Transfer</Button>);

    const button = screen.getByRole("button", { name: "Transfer" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
  });

  it("is enabled and not busy at rest", () => {
    render(<Button>Transfer</Button>);

    const button = screen.getByRole("button", { name: "Transfer" });
    expect(button).toBeEnabled();
    expect(button).not.toHaveAttribute("aria-busy");
  });
});
