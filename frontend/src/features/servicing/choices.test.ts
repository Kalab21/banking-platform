import { describe, expect, it } from "vitest";
import {
  cardPaymentChoices,
  nextInstalment,
  payoffChoices,
  repaymentChoices,
  servicingAccounts,
} from "@/features/servicing/choices";
import type { Account, AmortizationScheduleRow, CreditCard, PayoffQuote } from "@/types/api";

function row(n: number, status: AmortizationScheduleRow["status"], amountPaid = 0): AmortizationScheduleRow {
  return {
    id: n,
    loanId: 1,
    paymentNumber: n,
    dueDate: `2026-${String(n + 9).padStart(2, "0")}-01`,
    scheduledPayment: 180,
    principalPortion: 150,
    interestPortion: 30,
    remainingBalance: 4000 - 150 * n,
    amountPaid,
    status,
    paidAt: null,
  };
}

function card(over: Partial<CreditCard>): CreditCard {
  return {
    currentBalance: 0,
    statementBalance: 0,
    minimumPaymentDue: 0,
    paymentDueDate: null,
    currency: "USD",
    ...over,
  } as CreditCard;
}

describe("loan payment amounts", () => {
  it("offers the earliest unpaid instalment, whatever order the rows arrive in", () => {
    const next = nextInstalment([row(3, "PENDING"), row(1, "PAID", 180), row(2, "PENDING")]);
    expect(next?.row.paymentNumber).toBe(2);
    expect(next?.left).toBe(180);
  });

  it("offers only what is left on a part-paid instalment", () => {
    const [choice] = repaymentChoices([row(1, "PARTIAL", 50.25), row(2, "PENDING")]);
    expect(choice.label).toBe("Rest of instalment 1");
    expect(choice.amount).toBe(129.75);
  });

  it("offers no instalment once every one is paid", () => {
    expect(repaymentChoices([row(1, "PAID", 180)])).toEqual([]);
  });

  it("offers the stored payoff figure, and nothing without a quote", () => {
    const quote = { totalPayoffAmount: 3712.4, accruedInterest: 27.4 } as PayoffQuote;
    expect(payoffChoices(quote, "USD")[0].amount).toBe(3712.4);
    expect(payoffChoices(null, "USD")).toEqual([]);
  });
});

describe("card payment amounts", () => {
  it("offers minimum, statement and current balance", () => {
    const choices = cardPaymentChoices(card({ currentBalance: 640, statementBalance: 500, minimumPaymentDue: 25 }));
    expect(choices.map((c) => [c.id, c.amount])).toEqual([
      ["minimum", 25],
      ["statement", 500],
      ["current", 640],
    ]);
  });

  it("caps each at what is owed now and offers an amount once", () => {
    // Paid down since the statement: the statement balance is more than is owed.
    const choices = cardPaymentChoices(card({ currentBalance: 120, statementBalance: 500, minimumPaymentDue: 25 }));
    expect(choices.map((c) => [c.id, c.amount])).toEqual([
      ["minimum", 25],
      ["statement", 120],
    ]);
  });

  it("offers nothing on a card with no balance", () => {
    expect(cardPaymentChoices(card({ currentBalance: 0, statementBalance: 80, minimumPaymentDue: 25 }))).toEqual([]);
  });
});

describe("accounts a payment can use", () => {
  it("keeps open accounts in the product's currency", () => {
    const accounts = [
      { id: 1, currency: "USD", status: "ACTIVE" },
      { id: 2, currency: "USD", status: "OVERDRAWN" },
      { id: 3, currency: "USD", status: "FROZEN" },
      { id: 4, currency: "USD", status: "CLOSED" },
      { id: 5, currency: "EUR", status: "ACTIVE" },
    ] as Account[];
    expect(servicingAccounts(accounts, "USD").map((a) => a.id)).toEqual([1, 2]);
  });
});
