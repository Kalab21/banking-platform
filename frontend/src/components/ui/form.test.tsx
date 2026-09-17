import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Button, PasswordField, TextField } from "@/components/ui/form";
import { PasswordRequirements } from "@/components/ui/PasswordRequirements";

/**
 * The accessibility contract of the form primitives.
 *
 * These assert behaviour a screen reader or a keyboard depends on — label
 * binding, error association, the toggle's accessible name — and not the
 * classes that make it look the way it does. A restyle should not break them.
 */

describe("TextField", () => {
  it("binds its label to the control", async () => {
    render(<TextField label="Username" name="username" />);

    const input = screen.getByLabelText("Username");
    await userEvent.type(input, "ada");

    expect(input).toHaveValue("ada");
  });

  it("generates a unique id per instance, so two fields sharing a name stay addressable", () => {
    render(
      <>
        <TextField label="Amount to send" name="amount" />
        <TextField label="Amount to deposit" name="amount" />
      </>,
    );

    const first = screen.getByLabelText("Amount to send");
    const second = screen.getByLabelText("Amount to deposit");

    expect(first.id).not.toBe(second.id);
  });

  it("is not marked invalid until it has an error", () => {
    render(<TextField label="Email address" name="email" />);

    expect(screen.getByLabelText("Email address")).not.toHaveAttribute("aria-invalid");
  });

  it("announces its error and points the control at it", () => {
    render(<TextField label="Email address" name="email" error="Enter a valid email address" />);

    const input = screen.getByLabelText("Email address");
    const message = screen.getByRole("alert");

    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(message).toHaveTextContent("Enter a valid email address");
    expect(input.getAttribute("aria-describedby")).toContain(message.id);
  });

  it("references its hint as well as its error", () => {
    render(<TextField label="Username" name="username" hint="3–50 characters" error="Too short" />);

    const described = screen.getByLabelText("Username").getAttribute("aria-describedby") ?? "";

    expect(described.split(" ")).toHaveLength(2);
    expect(screen.getByText("3–50 characters")).toBeInTheDocument();
  });
});

describe("PasswordField", () => {
  it("masks the value by default", () => {
    render(<PasswordField label="Password" name="password" />);

    expect(screen.getByLabelText("Password")).toHaveAttribute("type", "password");
  });

  it("reveals the value and renames the toggle to describe what it now does", async () => {
    render(<PasswordField label="Password" name="password" />);

    const input = screen.getByLabelText("Password");
    await userEvent.click(screen.getByRole("button", { name: "Show password" }));

    expect(input).toHaveAttribute("type", "text");

    const toggle = screen.getByRole("button", { name: "Hide password" });
    expect(toggle).toHaveAttribute("aria-pressed", "true");

    await userEvent.click(toggle);
    expect(input).toHaveAttribute("type", "password");
  });

  it("keeps the toggle out of the way when the field is disabled", () => {
    render(<PasswordField label="Password" name="password" disabled />);

    expect(screen.getByRole("button", { name: "Show password" })).toBeDisabled();
  });
});

describe("Button", () => {
  it("disables itself and reports progress while pending, preventing a second submit", () => {
    render(
      <Button pending type="submit">
        Sign in
      </Button>,
    );

    const button = screen.getByRole("button", { name: "Sign in" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
  });

  it("is enabled and not busy at rest", () => {
    render(<Button>Sign in</Button>);

    const button = screen.getByRole("button", { name: "Sign in" });
    expect(button).toBeEnabled();
    expect(button).not.toHaveAttribute("aria-busy");
  });
});

describe("PasswordRequirements", () => {
  it("shows every rule as unmet for an empty value", () => {
    render(<PasswordRequirements value="" />);

    expect(screen.getByText("At least 8 characters")).toBeInTheDocument();
    expect(screen.getByText("One uppercase letter")).toBeInTheDocument();
    expect(screen.getByText("One lowercase letter")).toBeInTheDocument();
    expect(screen.getByText("One number")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("0 of 4");
  });

  it("counts rules as they are satisfied", () => {
    // Long enough, has lower and upper, no digit.
    render(<PasswordRequirements value="Passwordd" />);

    expect(screen.getByRole("status")).toHaveTextContent("3 of 4");
  });

  it("reports every rule met for a compliant password", () => {
    render(<PasswordRequirements value="Password123" />);

    expect(screen.getByRole("status")).toHaveTextContent("4 of 4");
  });
});
