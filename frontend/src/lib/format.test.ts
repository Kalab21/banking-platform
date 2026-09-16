import { describe, expect, it } from "vitest";
import {
  formatCurrency,
  formatPercent,
  humanise,
  isCredit,
  maskAccountNumber,
  maskCardNumber,
} from "@/lib/format";

describe("formatCurrency", () => {
  it("renders an amount with two decimal places", () => {
    expect(formatCurrency(1234.5)).toBe("$1,234.50");
  });

  it("keeps the sign on a negative balance, because overdrafts are real", () => {
    expect(formatCurrency(-35)).toBe("-$35.00");
  });

  it("renders a dash rather than NaN when a value is missing", () => {
    expect(formatCurrency(null)).toBe("—");
    expect(formatCurrency(undefined)).toBe("—");
    expect(formatCurrency(Number.NaN)).toBe("—");
  });
});

describe("maskCardNumber", () => {
  it("shows only the last four digits", () => {
    expect(maskCardNumber("4111111111111234")).toBe("•••• 1234");
  });

  it("never echoes an input it cannot safely mask", () => {
    expect(maskCardNumber("12")).toBe("•••• ••••");
    expect(maskCardNumber("")).toBe("•••• ••••");
    expect(maskCardNumber(null)).toBe("•••• ••••");
  });

  it("does not leak the full number for any input", () => {
    const pan = "4111111111111234";
    expect(maskCardNumber(pan)).not.toContain("411111");
  });
});

describe("maskAccountNumber", () => {
  it("shows only the last four characters", () => {
    expect(maskAccountNumber("BA250101000123")).toBe("••••0123");
  });

  it("returns a dash when there is nothing to show", () => {
    expect(maskAccountNumber(null)).toBe("—");
  });
});

describe("humanise", () => {
  it("turns backend enum values into readable labels", () => {
    expect(humanise("PERSONAL_LOAN")).toBe("Personal Loan");
    expect(humanise("OVERDRAWN")).toBe("Overdrawn");
  });

  it("falls back to a dash for missing values", () => {
    expect(humanise(null)).toBe("—");
  });
});

describe("formatPercent", () => {
  it("renders a rate to two decimal places", () => {
    expect(formatPercent(6)).toBe("6.00%");
  });
});

describe("isCredit", () => {
  it("treats deposits and incoming transfers as money in", () => {
    expect(isCredit("DEPOSIT")).toBe(true);
    expect(isCredit("TRANSFER_IN")).toBe(true);
  });

  it("treats withdrawals and outgoing transfers as money out", () => {
    expect(isCredit("WITHDRAWAL")).toBe(false);
    expect(isCredit("TRANSFER_OUT")).toBe(false);
  });
});
