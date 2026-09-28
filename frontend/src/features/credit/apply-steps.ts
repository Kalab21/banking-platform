import { formatCurrency } from "@/lib/format";
import type { CreditProduct } from "@/features/credit/products";

/**
 * The application journey, one question group at a time.
 *
 * Checking here is for the customer's sake: it catches a typo on the step
 * where it was made instead of after the whole form is sent. It decides
 * nothing. The backend validates every field again and underwriting makes the
 * decision; nothing here can approve, refer or refuse.
 */

export type StepId = "need" | "finances" | "review";

export interface Step {
  id: StepId;
  title: string;
}

export type Values = Record<string, string>;

export const FIELDS = [
  "requestedAmount",
  "termMonths",
  "assetValue",
  "downPayment",
  "purpose",
  "annualIncome",
  "monthlyDebtObligations",
] as const;

export function stepsFor(product: CreditProduct): Step[] {
  return [
    { id: "need", title: product.type === "CREDIT_CARD" ? "About the card" : "What you need" },
    { id: "finances", title: "Your finances" },
    { id: "review", title: "Check and submit" },
  ];
}

export function initialValues(product: CreditProduct): Values {
  return {
    requestedAmount: "",
    termMonths: product.terms ? String(product.terms[0]) : "",
    assetValue: "",
    downPayment: "",
    purpose: "",
    annualIncome: "",
    monthlyDebtObligations: "",
  };
}

const MONEY = /^\d+(\.\d{1,2})?$/;

/** A typed amount as a number, or null when it is not one. Commas are allowed. */
export function amountOf(raw: string): number | null {
  const plain = raw.trim().replace(/,/g, "");
  return MONEY.test(plain) ? Number(plain) : null;
}

function required(raw: string, label: string, errors: Values, key: string, allowZero = false) {
  if (raw.trim() === "") {
    errors[key] = `Enter ${label}`;
    return;
  }
  const value = amountOf(raw);
  if (value === null) errors[key] = "Enter an amount in dollars and cents, for example 1250.00";
  else if (!allowZero && value <= 0) errors[key] = "Enter an amount greater than zero";
}

export function validateStep(product: CreditProduct, step: StepId, values: Values): Values {
  const errors: Values = {};
  if (step === "need") {
    if (product.asks.amount) required(values.requestedAmount, "how much you would like to borrow", errors, "requestedAmount");
    if (product.asks.term && product.terms && !product.terms.includes(Number(values.termMonths))) {
      errors.termMonths = "Choose one of the terms offered";
    }
    if (product.asks.asset) required(values.assetValue, "the value", errors, "assetValue");
    if (product.asks.downPayment && values.downPayment.trim() !== "") {
      const down = amountOf(values.downPayment);
      const asset = amountOf(values.assetValue);
      if (down === null) errors.downPayment = "Enter an amount in dollars and cents, or leave it blank";
      else if (asset !== null && down >= asset) errors.downPayment = "The deposit must be less than the value";
    }
    if (product.type === "PERSONAL_LOAN" && values.purpose.trim() === "") {
      errors.purpose = "Tell us what the loan is for";
    }
    if (values.purpose.length > 200) errors.purpose = "Keep this to 200 characters";
  }
  if (step === "finances") {
    required(values.annualIncome, "your annual income", errors, "annualIncome");
    // Nothing owed is a real answer.
    required(values.monthlyDebtObligations, "what you pay towards other debts, or 0", errors, "monthlyDebtObligations", true);
  }
  return errors;
}

export interface ReviewLine {
  label: string;
  value: string;
  step: StepId;
}

/** Everything the customer stated, in their words, for the last check before sending. */
export function reviewLines(product: CreditProduct, values: Values): ReviewLine[] {
  const money = (raw: string) => {
    const value = amountOf(raw);
    return value === null ? raw : formatCurrency(value, "USD");
  };
  const lines: ReviewLine[] = [];
  if (product.asks.amount) lines.push({ label: "Amount", value: money(values.requestedAmount), step: "need" });
  if (product.asks.term) lines.push({ label: "Term", value: `${values.termMonths} months`, step: "need" });
  if (product.asks.asset) {
    lines.push({
      label: product.type === "MORTGAGE" ? "Property value" : "Vehicle value",
      value: money(values.assetValue),
      step: "need",
    });
  }
  if (product.asks.downPayment) {
    lines.push({
      label: "Deposit",
      value: values.downPayment.trim() ? money(values.downPayment) : "None",
      step: "need",
    });
  }
  if (product.asks.purpose) {
    lines.push({ label: "Purpose", value: values.purpose.trim() || "Not given", step: "need" });
  }
  lines.push({ label: "Annual income", value: money(values.annualIncome), step: "finances" });
  lines.push({ label: "Monthly debt payments", value: money(values.monthlyDebtObligations), step: "finances" });
  return lines;
}
