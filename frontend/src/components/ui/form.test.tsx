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
    expect(input).toHaveAttribute("aria-describedby", "username-error");
    expect(screen.getByRole("alert")).toHaveTextContent("Enter your username");
  });

  it("is not marked invalid when there is no error", () => {
    render(<TextField label="Username" name="username" />);
    expect(screen.getByLabelText("Username")).not.toHaveAttribute("aria-invalid");
  });

  it("describes the input by its hint when one is given", () => {
    render(<TextField label="Phone" name="phone" hint="Optional" />);
    expect(screen.getByLabelText("Phone")).toHaveAttribute("aria-describedby", "phone-hint");
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
