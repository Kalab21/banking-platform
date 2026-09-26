import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ApplyForm } from "./ApplyForm";
import { CREDIT_PRODUCTS } from "./products";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/features/credit/actions", () => ({ applyForCreditAction: vi.fn() }));

describe.each(CREDIT_PRODUCTS)("$name form requirements", (product) => {
  it("requires all fields mandated by the backend and leaves optional fields optional", () => {
    render(<ApplyForm product={product} />);
    const income = screen.getByLabelText("Your annual income before tax");
    const debt = screen.getByLabelText("What you already pay each month towards other debts");
    expect(income).toBeRequired();
    expect(income).toBeInvalid();
    expect(debt).toBeRequired();
    expect(debt).toBeInvalid();

    if (product.type !== "CREDIT_CARD") {
      expect(screen.getByLabelText("How much would you like to borrow?")).toBeRequired();
      expect(screen.getByLabelText("Over how long?")).toBeRequired();
    } else {
      expect(screen.queryByLabelText("How much would you like to borrow?")).not.toBeInTheDocument();
      expect(screen.queryByLabelText("Over how long?")).not.toBeInTheDocument();
    }

    if (product.type === "AUTO_LOAN" || product.type === "MORTGAGE") {
      expect(screen.getByLabelText(/Value of the/)).toBeRequired();
      expect(screen.getByLabelText("Deposit you are putting down")).not.toBeRequired();
    }
    if (product.type === "PERSONAL_LOAN") expect(screen.getByLabelText("What is it for?")).toBeRequired();
    if (product.type === "CREDIT_CARD") expect(screen.getByLabelText("What is it for?")).not.toBeRequired();
  });
});
