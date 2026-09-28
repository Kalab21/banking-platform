import { describe, expect, it } from "vitest";
import { amountOf, initialValues, reviewLines, validateStep } from "@/features/credit/apply-steps";
import { creditProduct } from "@/features/credit/products";

const loan = creditProduct("PERSONAL_LOAN")!;
const auto = creditProduct("AUTO_LOAN")!;
const card = creditProduct("CREDIT_CARD")!;

describe("amounts a customer types", () => {
  it.each([
    ["1250", 1250],
    ["1,250.50", 1250.5],
    [" 0 ", 0],
  ])("reads %j as %d", (raw, value) => expect(amountOf(raw)).toBe(value));

  it.each(["", "12.345", "-5", "1e3", "abc"])("refuses %j", (raw) => expect(amountOf(raw)).toBeNull());
});

describe("step checks", () => {
  it("asks a personal loan applicant for an amount and a purpose", () => {
    const errors = validateStep(loan, "need", initialValues(loan));
    expect(Object.keys(errors).sort()).toEqual(["purpose", "requestedAmount"]);
  });

  it("offers only the product's own terms", () => {
    const values = { ...initialValues(loan), requestedAmount: "4000", purpose: "Car", termMonths: "7" };
    expect(validateStep(loan, "need", values)).toEqual({ termMonths: "Choose one of the terms offered" });
  });

  it("refuses a deposit that is not less than the value", () => {
    const values = { ...initialValues(auto), requestedAmount: "20000", assetValue: "25000", downPayment: "25000" };
    expect(validateStep(auto, "need", values)).toEqual({ downPayment: "The deposit must be less than the value" });
  });

  it("asks a card applicant nothing they must answer about the card", () => {
    expect(validateStep(card, "need", initialValues(card))).toEqual({});
  });

  it("accepts no other debts as an answer, but not a blank", () => {
    const blank = validateStep(card, "finances", { ...initialValues(card), annualIncome: "72000" });
    expect(Object.keys(blank)).toEqual(["monthlyDebtObligations"]);
    expect(validateStep(card, "finances", { ...initialValues(card), annualIncome: "72000", monthlyDebtObligations: "0" })).toEqual({});
  });

  it("refuses a sub-cent income", () => {
    const errors = validateStep(card, "finances", { ...initialValues(card), annualIncome: "72000.005", monthlyDebtObligations: "0" });
    expect(errors.annualIncome).toMatch(/dollars and cents/);
  });
});

describe("the review", () => {
  it("repeats what the customer stated and nothing the bank decides", () => {
    const lines = reviewLines(loan, {
      ...initialValues(loan),
      requestedAmount: "4,000",
      termMonths: "24",
      purpose: "Kitchen repair",
      annualIncome: "90000",
      monthlyDebtObligations: "450",
    });
    expect(lines.map((l) => [l.label, l.value])).toEqual([
      ["Amount", "$4,000.00"],
      ["Term", "24 months"],
      ["Purpose", "Kitchen repair"],
      ["Annual income", "$90,000.00"],
      ["Monthly debt payments", "$450.00"],
    ]);
  });
});
